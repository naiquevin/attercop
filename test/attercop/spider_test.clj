(ns attercop.spider-test
  (:require [clojure.test :refer :all]
            [attercop.test-site :as site]
            [attercop.enlive-utils :as eu]
            [attercop.spider :as spider]))


(defn- start-test-site-server
  [port]
  (let [sitemap {:index [:a :b :c :d]
                 :a [:a-1 :a-2 :a-3 :a-4 {:text "a-5"
                                          :status 404}]
                 :b [:b-1 :b-2]
                 :c [:c-1 :c-2 :c-3 {:text "Clojure Docs"
                                     :href "http://clojure.org/documentation"}]
                 :d [{:text "Back to home" :href "/index.html"}
                     :d-1
                     :w-x-s
                     :dont-scrape
                     {:text "Github" :href "https://github.com/"}]
                 :dont-scrape [:e]
                 :e [:e-1]}]
    (site/start-server (site/sitemap->handler sitemap) {:port port})))


(defn- stop-test-site-server []
  (site/stop-server))


(deftest spider-test
  (let [port 5050
        parse-node (fn [{:keys [html-nodes]}]
                     (let [h1 (first (eu/extract-texts html-nodes [:h1]))
                           links (eu/extract-texts html-nodes [:a])]
                       [{:title h1 :links links}]))
        parse-leaf (fn [{:keys [html-nodes]}]
                     [{:title (first (eu/extract-texts html-nodes [:h1]))}])
        wrap-num-links (fn [{:keys [links] :as data}]
                         (assoc data :num-links (count links)))
        result (atom {})
        collect-result (fn [{:keys [title] :as data}]
                         (swap! result assoc title data))
        spider-conf {:name "test-spider"
                     :allowed-domains #{"localhost" "127.0.0.1"}
                     :start-urls [(format "http://127.0.0.1:%s/index.html" port)]
                     :rules [[#"/dont-scrape.html$" {:scrape nil
                                                     :follow true}]
                             [#"/([a-zA-Z]+.html)?$" {:scrape parse-node
                                                      :follow true}]
                             [#"/\w+-\d+.html" {:scrape parse-leaf
                                                :follow true}]
                             [:default {:scrape nil :follow false}]]
                     :pipeline [wrap-num-links collect-result]
                     :max-wait 5000}]
    (start-test-site-server port)
    (spider/run spider-conf)
    (testing "Testing the spider on a test site."
      (is (= 17 (count @result)))
      (is (= (@result "index")
             {:title "index" :links ["a" "b" "c" "d"] :num-links 4}))
      (is (= (@result "a")
             {:title "a" :links ["a-1" "a-2" "a-3" "a-4" "a-5"] :num-links 5}))
      (is (= (@result "b")
             {:title "b" :links ["b-1" "b-2"] :num-links 2}))
      (is (= (@result "c")
             {:title "c" :links ["c-1" "c-2" "c-3" "Clojure Docs"] :num-links 4}))
      (is (= (@result "d")
             {:title "d" :links ["Back to home" "d-1" "w-x-s" "dont-scrape" "Github"]
              :num-links 5}))
      (is (= (@result "e")
             {:title "e" :links ["e-1"] :num-links 1}))
      (is (every? #(nil? (@result %)) ["a-5" "dont-scrape" "Github" "Clojure Docs"])))
    (stop-test-site-server)))
