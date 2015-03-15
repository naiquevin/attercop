(ns attercop.examples.dmoz-lisp
  (:require [clojure.string :as s]
            [attercop.spider :as spider]
            [net.cgrand.enlive-html :as html]))


(defn parse
  [{:keys [html-nodes]}]
  (let [list (first (html/select html-nodes [:.directory-url]))
        lang (last (:content (first (html/select html-nodes
                                                 [:.navigate
                                                  :.last
                                                  :strong]))))]
    (map (fn [item]
           (let [anchor (first (html/select item [:a.listinglink]))]
             {:lang lang
              :title (first (:content anchor))
              :url (get-in anchor [:attrs :href])
              :description (->> (:content item)
                                (filter string?)
                                (map s/trim)
                                (apply str))}))
         (html/select list [:li]))))


(defn -main
  [& args]
  (let [config {:name "dmoz scraper for lisp resources"
                :allowed-domains #{"www.dmoz.org"}
                :start-urls ["http://www.dmoz.org/Computers/Programming/Languages/"]
                :rules [[#"Computers/Programming/Languages/Functional/"
                         {:scrape nil :follow true}]
                        [#"Computers/Programming/Languages/Lisp/?.*"
                         {:scrape parse :follow false}]
                        [:default {:scrape nil :follow false}]]
                :pipeline [prn]
                :max-wait 5000
                :rate-limit [5 3000]}]
    (spider/run config)))
