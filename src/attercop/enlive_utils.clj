(ns attercop.enlive-utils
  "Utilities to work with html using enlive."
  (:require [net.cgrand.enlive-html :as html])
  (:import [java.io StringReader]))


(defn html->nodes
  "Returns enlive data structure representing the DOM from HTML in
  string format"
  [html-str]
  (html/html-resource (StringReader. html-str)))


(defn extract-hrefs
  "Returns the hrefs of all anchor tags from enlive html nodes."
  [nodes]
  (map #(get-in % [:attrs :href]) (html/select nodes [:a])))


(defn extract-texts
  "Returns text content of all nodes matching selector."
  [nodes selector]
  (map (comp first :content) (html/select nodes selector)))
