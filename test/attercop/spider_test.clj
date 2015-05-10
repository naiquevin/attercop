(ns attercop.spider-test
  (:require [clojure.test :refer :all]
            [attercop.mock-site :as site]
            [attercop.enlive-utils :as eu]
            [attercop.spider :as spider]))


(defn- start-test-site-server
  [port]
  (let [sitemap {:index [:a :b :c :d]
                 :a [:a-1 :a-2 :a-3 :a-4 {:title "a-5"
                                          :status 404}]
                 :b [:b-1 :b-2 {:title "b-3" :status 500}]
                 :c [:c-1 :c-2 :c-3 {:title "Clojure Docs"
                                     :href "http://clojure.org/documentation"}]
                 :d [{:title "Back to home" :href "/index.html"}
                     :d-1
                     :w-x-s
                     :dont-scrape
                     {:title "Github" :href "https://github.com/"}]
                 :dont-scrape [:e]
                 :e [:e-1]}]
    (site/start-server (site/sitemap->handler sitemap) {:port port})))


(defn- stop-test-site-server []
  (site/stop-server))


(defn- parse-node
  [{:keys [html-nodes]}]
  (let [h1 (first (eu/extract-texts html-nodes [:h1]))
        links (eu/extract-texts html-nodes [:a])]
    [{:title h1 :links links}]))


(defn- parse-leaf
  [{:keys [html-nodes]}]
  [{:title (first (eu/extract-texts html-nodes [:h1]))}])


(defn- wrap-num-links
  [{:keys [links] :as data}]
  (assoc data :num-links (count links)))


(defn- collect-result
  [result {:keys [title] :as data}]
  (swap! result assoc title data))


(deftest basic-spider
  (let [port 5050
        result (atom {})
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
                     :pipeline [wrap-num-links
                                (partial collect-result result)]
                     :max-wait 5000
                     :rate-limit [20 1000]
                     :graceful-shutdown? false
                     :handle-status-codes #{500}}]
    (start-test-site-server port)
    (spider/run spider-conf)
    (testing "Testing the spider on a test site."
      (is (= 18 (count @result)))
      (is (= (@result "index")
             {:title "index" :links ["a" "b" "c" "d"] :num-links 4}))
      (is (= (@result "a")
             {:title "a" :links ["a-1" "a-2" "a-3" "a-4" "a-5"] :num-links 5}))
      (is (= (@result "b")
             {:title "b" :links ["b-1" "b-2" "b-3"] :num-links 3}))
      (is (= (@result "c")
             {:title "c" :links ["c-1" "c-2" "c-3" "Clojure Docs"] :num-links 4}))
      (is (= (@result "d")
             {:title "d" :links ["Back to home" "d-1" "w-x-s" "dont-scrape" "Github"]
              :num-links 5}))
      (is (= (@result "e")
             {:title "e" :links ["e-1"] :num-links 1}))
      (is (every? #(nil? (@result %)) ["a-5" "dont-scrape" "Github" "Clojure Docs"])))
    (stop-test-site-server)))


(deftest spider-with-follow-function
  []
  (let [port 5050
        result (atom {})
        follow-fn (constantly ["/a.html" "/c.html"])
        spider-conf {:name "test-spider"
                     :allowed-domains #{"localhost" "127.0.0.1"}
                     :start-urls [(format "http://127.0.0.1:%s/index.html" port)]
                     :rules [[#"/index.html" {:scrape parse-node
                                              :follow follow-fn}]
                             [#"/([a-zA-Z]+.html)?$" {:scrape parse-node
                                                      :follow false}]
                             [:default {:scrape nil :follow false}]]
                     :pipeline [wrap-num-links
                                (partial collect-result result)]
                     :max-wait 5000
                     :rate-limit [20 1000]
                     :graceful-shutdown? false
                     :handle-status-codes #{500}}]
    (start-test-site-server port)
    (spider/run spider-conf)
    (testing "Testing spider with follow function."
      (is (= 3 (count @result)))
      (is (= (@result "index")
             {:title "index" :links ["a" "b" "c" "d"] :num-links 4}))
      (is (= (@result "a")
             {:title "a" :links ["a-1" "a-2" "a-3" "a-4" "a-5"] :num-links 5}))
      (is (= (@result "c")
             {:title "c" :links ["c-1" "c-2" "c-3" "Clojure Docs"] :num-links 4}))
      (is (nil? (@result "b")))
      (is (nil? (@result "d")))
      (is (nil? (@result "e"))))
    (stop-test-site-server)))
