(ns attercop.enlive-utils
  (:require [net.cgrand.enlive-html :as html])
  (:import [java.io StringReader]))


(defn html->nodes
  [html-str]
  (html/html-resource (StringReader. html-str)))


(defn extract-hrefs
  [nodes]
  (map #(get-in % [:attrs :href]) (html/select nodes [:a])))


(defn extract-texts
  [nodes selector]
  (map (comp first :content) (html/select nodes selector)))
