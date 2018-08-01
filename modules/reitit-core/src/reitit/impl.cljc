; Copyright 2013 Relevance, Inc.
; Copyright 2014-2016 Cognitect, Inc.

; The use and distribution terms for this software are covered by the
; Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0)
; which can be found in the file epl-v10.html at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

(ns ^:no-doc reitit.impl
  #?(:cljs (:require-macros [reitit.impl]))
  (:require [clojure.string :as str]
            [clojure.set :as set])
  #?(:clj
     (:import (java.util.regex Pattern)
              (java.util HashMap Map)
              (java.net URLEncoder URLDecoder))))

(defn wild? [s]
  (contains? #{\: \*} (first (str s))))

(defn catch-all? [s]
  (= \* (first (str s))))

(defn wild-param [s]
  (let [ss (str s)]
    (if (= \: (first ss))
      (keyword (subs ss 1)))))

(defn catch-all-param [s]
  (let [ss (str s)]
    (if (= \* (first ss))
      (keyword (subs ss 1)))))

(defn wild-or-catch-all-param? [x]
  (boolean (or (wild-param x) (catch-all-param x))))

(defn segments [path]
  #?(:clj  (.split ^String path "/" 666)
     :cljs (.split path #"/" 666)))

(defn contains-wilds? [path]
  (boolean (some wild-or-catch-all-param? (segments path))))

;;
;; https://github.com/pedestal/pedestal/blob/master/route/src/io/pedestal/http/route/path.clj
;;

(defn- parse-path-token [out string]
  (condp re-matches string
    #"^:(.+)$" :>> (fn [[_ token]]
                     (let [key (keyword token)]
                       (-> out
                           (update-in [:path-parts] conj key)
                           (update-in [:path-params] conj key)
                           (assoc-in [:path-constraints key] "([^/]+)"))))
    #"^\*(.*)$" :>> (fn [[_ token]]
                      (let [key (keyword token)]
                        (-> out
                            (update-in [:path-parts] conj key)
                            (update-in [:path-params] conj key)
                            (assoc-in [:path-constraints key] "(.*)"))))
    (update-in out [:path-parts] conj string)))

(defn- parse-path
  ([pattern] (parse-path {:path-parts [] :path-params [] :path-constraints {}} pattern))
  ([accumulated-info pattern]
   (if-let [m (re-matches #"/(.*)" pattern)]
     (let [[_ path] m]
       (reduce parse-path-token
               accumulated-info
               (str/split path #"/")))
     (throw (ex-info "Routes must start from the root, so they must begin with a '/'" {:pattern pattern})))))

;; TODO: is this correct?
(defn- re-quote [x]
  #?(:clj  (Pattern/quote x)
     :cljs (str/replace-all x #"([.?*+^$[\\]\\\\(){}|-])" "\\$1")))

(defn- path-regex [{:keys [path-parts path-constraints] :as route}]
  (let [[pp & pps] path-parts
        path-parts (if (and (seq pps) (string? pp) (empty? pp)) pps path-parts)]
    (re-pattern
      (apply str
             (interleave (repeat "/")
                         (map #(or (get path-constraints %) (re-quote %))
                              path-parts))))))

(defn- path-matcher [route]
  (let [{:keys [path-re path-params]} route]
    (fn [path]
      (when-let [m (re-matches path-re path)]
        (zipmap path-params (rest m))))))

;;
;; Routing (c) Metosin
;;

(defrecord Route [path matcher path-parts path-params data result])

(defn create [[path data result]]
  (let [path #?(:clj (.intern ^String path) :cljs path)]
    (as-> (parse-path path) $
          (assoc $ :path-re (path-regex $))
          (merge $ {:path path
                    :matcher (if (contains-wilds? path)
                               (path-matcher $)
                               #(if (#?(:clj .equals, :cljs =) path %) {}))
                    :result result
                    :data data})
          (dissoc $ :path-re :path-constraints)
          (update $ :path-params set)
          (map->Route $))))

(defn wild-route? [[path]]
  (contains-wilds? path))

(defn conflicting-routes? [[p1] [p2]]
  (loop [[s1 & ss1] (segments p1)
         [s2 & ss2] (segments p2)]
    (cond
      (= s1 s2 nil) true
      (or (nil? s1) (nil? s2)) false
      (or (catch-all? s1) (catch-all? s2)) true
      (or (wild? s1) (wild? s2)) (recur ss1 ss2)
      (not= s1 s2) false
      :else (recur ss1 ss2))))

(defn path-for [^Route route path-params]
  (if-let [required (:path-params route)]
    (if (every? #(contains? path-params %) required)
      (->> (:path-parts route)
           (map #(get (or path-params {}) % %))
           (str/join \/)
           (str "/")))
    (:path route)))

(defn throw-on-missing-path-params [template required path-params]
  (when-not (every? #(contains? path-params %) required)
    (let [defined (-> path-params keys set)
          missing (set/difference required defined)]
      (throw
        (ex-info
          (str "missing path-params for route " template " -> " missing)
          {:path-params path-params, :required required})))))

(defn fast-assoc
  #?@(:clj  [[^clojure.lang.Associative a k v] (.assoc a k v)]
      :cljs [[a k v] (assoc a k v)]))

(defn fast-map [m]
  #?(:clj  (let [m (or m {})] (HashMap. ^Map m))
     :cljs m))

(defn fast-get
  #?@(:clj  [[^HashMap m k] (.get m k)]
      :cljs [[m k] (m k)]))

(defn strip-nils [m]
  (->> m (remove (comp nil? second)) (into {})))

;;
;; Parts (c) https://github.com/lambdaisland/uri/tree/master/src/lambdaisland/uri
;;

#?(:clj
   (def hex-digit
     {0 "0" 1 "1" 2 "2" 3 "3"
      4 "4" 5 "5" 6 "6" 7 "7"
      8 "8" 9 "9" 10 "A" 11 "B"
      12 "C" 13 "D" 14 "E" 15 "F"}))

#?(:clj
   (defn byte->percent [byte]
     (let [byte (bit-and 0xFF byte)
           low-nibble (bit-and 0xF byte)
           high-nibble (bit-shift-right byte 4)]
       (str "%" (hex-digit high-nibble) (hex-digit low-nibble)))))

#?(:clj
   (defn percent-encode [^String unencoded]
     (->> (.getBytes unencoded "UTF-8") (map byte->percent) (str/join))))

;;
;; encoding & decoding
;;

;; + is safe, but removed so it would work the same as with js
(defn url-encode [s]
  (if s
    #?(:clj  (str/replace s #"[^A-Za-z0-9\!'\(\)\*_~.-]+" percent-encode)
       :cljs (js/encodeURIComponent s))))

(defn url-decode [s]
  (if s
    #?(:clj  (if (.contains ^String s "%")
               (URLDecoder/decode
                 (if (.contains ^String s "+")
                   (.replace ^String s "+" "%2B")
                   s)
                 "UTF-8")
               s)
       :cljs (js/decodeURIComponent s))))

(defn form-encode [s]
  (if s
    #?(:clj  (URLEncoder/encode ^String s "UTF-8")
       :cljs (str/replace (js/encodeURIComponent s) "%20" "+"))))

(defn form-decode [s]
  (if s
    #?(:clj  (if (or (.contains ^String s "%") (.contains ^String s "+"))
               (URLDecoder/decode ^String s "UTF-8")
               s)
       :cljs (js/decodeURIComponent (str/replace s "+" " ")))))

(defprotocol IntoString
  (into-string [_]))

(extend-protocol IntoString
  #?(:clj  String
     :cljs string)
  (into-string [this] this)

  #?(:clj  clojure.lang.Keyword
     :cljs cljs.core.Keyword)
  (into-string [this]
    (let [ns (namespace this)]
      (str ns (if ns "/") (name this))))

  #?(:clj  Boolean
     :cljs boolean)
  (into-string [this] (str this))

  #?(:clj  Number
     :cljs number)
  (into-string [this] (str this))

  #?(:clj  Object
     :cljs object)
  (into-string [this] (str this))

  nil
  (into-string [_]))

(defn path-params
  "shallow transform of the path parameters values into strings"
  [params]
  (reduce-kv
    (fn [m k v]
      (assoc m k (url-encode (into-string v))))
    {}
    params))

(defn query-string
  "shallow transform of query parameters into query string"
  [params]
  (->> params
       (map (fn [[k v]]
              (str (form-encode (into-string k))
                   "="
                   (form-encode (into-string v)))))
       (str/join "&")))

(defmacro goog-extend [type base-type ctor & methods]
  `(do
     (def ~type (fn ~@ctor))

     (goog/inherits ~type ~base-type)

     ~@(map
         (fn [method]
           `(set! (.. ~type -prototype ~(symbol (str "-" (first method))))
                  (fn ~@(rest method))))
         methods)))
