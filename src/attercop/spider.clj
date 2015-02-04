(ns attercop.spider
  (:require [clj-http.client :as http]
            [clojure.core.async
             :refer [chan >! >!! <!! go thread close! timeout alts!]]
            [clojurewerkz.urly.core :as urly]
            [attercop.enlive-utils :as enlive-utils]))


(defn normalize-href
  [referrer href]
  (urly/absolutize href referrer))


(defn allowed-domain?
  [allowed-domains url]
  (allowed-domains (.getHost (urly/url-like url))))


(defn fetch-url
  [url]
  {:url url
   :html (:body (http/get url))})


(defn run-scrapers
  [rules {:keys [url html] :as resp}]
  (when-let [rule (first rules)]
    (let [[check {scrape :scrape}] rule]
      (if (or (= check :default) (re-find check url))
        (when scrape
          (scrape resp))
        (recur (rest rules) resp)))))


(defn follow-url?
  [rules url]
  (when-let [rule (first rules)]
    (let [[check {follow :follow}] rule]
      (if (or (= check :default) (re-find check url))
        (boolean follow)
        (recur (rest rules) url)))))


(defn skip-url?
  [rules url]
  (when-let [rule (first rules)]
    (let [[check {:keys [scrape follow]}] rule]
      (if (or (= check :default) (re-find check url))
        (not (or scrape follow))
        (recur (rest rules) url)))))


;; TODO! Use channels for pipeline as it may involve I/O
(defn pipe-results
  [pipeline result]
  (when-let [func (first pipeline)]
    (let [new-result (mapv func result)]
      (recur (rest pipeline) new-result))))


(defn start
  [{:keys [start-urls allowed-domains rules pipeline max-wait]
    :as config}]
  (let [ch-urls (chan)
        ch-resp (chan)
        scrape (partial run-scrapers rules)
        start-url-set (set start-urls)
        visited-urls (atom #{})
        follow? (fn [url]
                  (or (start-url-set url)
                      (follow-url? rules url)))
        skip? (fn [url]
                (or (@visited-urls url)
                    (not (allowed-domain? allowed-domains url))
                    (skip-url? rules url)))
        pipe (partial pipe-results pipeline)]
    ;; put urls into the urls channel
    (go (doseq [url start-urls]
          (>! ch-urls url)))
    ;; take urls from urls channel and put into the response channel
    (go (loop []
          (when-let [url (first (alts! [ch-urls (timeout max-wait)]))]
            (thread (>!! ch-resp (fetch-url url)))
            (swap! visited-urls conj url)
            (recur))))
    ;; consume the responses channel and do 2 things:
    ;; 1. scrape the urls and put then into the urls channel,
    ;; 2. apply rules and call the relevant callback fns
    [(go (loop []
            (when-let [{:keys [html url] :as resp}
                       (first (alts! [ch-resp (timeout max-wait)]))]
              (let [html-nodes (enlive-utils/html->nodes html)
                    resp (assoc resp :html-nodes html-nodes)
                    links (when (follow? url)
                            (->> (enlive-utils/extract-hrefs html-nodes)
                                 (map (partial normalize-href url))
                                 (remove skip?)))]
                (doseq [link links]
                  (go (>! ch-urls link)))
                (pipe (scrape resp)))
              (recur))))
     ch-urls]))


(defn run
  [config]
  (let [[ch-main ch-urls] (start config)]
    ;; TODO! catch sigint and sigterm here and close ch-urls. That's
    ;; why start returns 2 channels, we wait on ch-main, close ch-urls
    ;; when sigint or sigterm are intercepted.
    (<!! ch-main)))
