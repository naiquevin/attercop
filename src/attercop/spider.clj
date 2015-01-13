(ns attercop.spider
  (:require [clj-http.client :as http]
            [clojure.core.async
             :refer [chan >! <!! go close! timeout alts!]]
            [clojurewerkz.urly.core :as urly]
            [attercop.enlive-utils :as enlive-utils]))


(defn normalize-href
  [referrer href]
  (urly/absolutize href referrer))


(defn scrape
  [url]
  {:url url
   :html (:body (http/get url))})


(defn exec-callback
  [rules {:keys [url html] :as resp}]
  (when-let [rule (first rules)]
    (let [[regex func] rule]
      (if (re-find regex url)
        (func resp)
        (recur (rest rules) resp)))))


;; TODO! Use channels for pipeline as it may involve I/O
(defn pipe-results
  [pipeline result]
  (when-let [func (first pipeline)]
    (let [new-result (mapv func result)]
      (recur (rest pipeline) new-result))))


(defn start
  [{:keys [start-urls rules pipeline wait-ms] :as config}]
  (let [ch-urls (chan)
        ch-resp (chan)
        callback (partial exec-callback rules)
        pipe (partial pipe-results pipeline)]
    ;; put urls to the urls channel
    (go (doseq [url start-urls]
          (>! ch-urls url)))
    ;; take urls from urls channel and put into the response channel
    (go (loop []
          (when-let [url (first (alts! [ch-urls (timeout wait-ms)]))]
            (go (>! ch-resp (scrape url)))
            (recur))))
    ;; consume the responses channel and do 2 things:
    ;; 1. scrape the urls and put then into the urls channel,
    ;; 2. apply rules and call the relevant callback fns
    [(go (loop []
            (when-let [{:keys [html url] :as resp}
                       (first (alts! [ch-resp (timeout wait-ms)]))]
              (let [html-nodes (enlive-utils/html->nodes html)
                    resp (assoc resp :html-nodes html-nodes)
                    links (map (partial normalize-href url)
                               (enlive-utils/extract-hrefs html-nodes))]
                (doseq [link links]
                  (go (>! ch-urls link)))
                (pipe (callback resp)))
              (recur))))
     ch-urls]))


(defn run
  [config]
  (let [[ch-main ch-urls] (start config)]
    ;; TODO! catch sigint and sigterm here and close ch-urls. That's
    ;; why start returns 2 channels, we wait on ch-main, close ch-urls
    ;; when sigint or sigterm are intercepted.
    (<!! ch-main)))
