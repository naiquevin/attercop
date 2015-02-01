(ns attercop.core
  (:require [clojure.string :as s]
            [attercop.enlive-utils :as enlive-utils]
            [attercop.spider :as spider]))


(defn parse-node
  [{:keys [html-nodes]}]
  (let [h1 (first (enlive-utils/extract-texts html-nodes [:h1]))
        links (enlive-utils/extract-texts html-nodes [:a])]
    [{:title h1 :links links}]))


(defn parse-leaf
  [{:keys [html-nodes]}]
  [{:title (first (enlive-utils/extract-texts html-nodes [:h1]))}])


(defn wrap-num-links
  [{:keys [links] :as result}]
  (assoc result :num-links (count links)))


(defn -main
  [& args]
  (let [config {:name "test-spider"
                :allowed-domains #{"localhost" "127.0.0.1"}
                :start-urls ["http://127.0.0.1:5000/"]
                :rules [[#"[a-zA-Z]+.html$" parse-node]
                        [#"\w+-\d+.html" parse-leaf]]
                :pipeline [wrap-num-links
                           prn]
                :wait-ms 5000}]
    (spider/run config)))
