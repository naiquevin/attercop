(ns attercop.spider
  (:require [clojure.string :as s]
            [org.httpkit.client :as http]
            [clojure.core.async
             :refer [chan >! >!! <!! go thread close! timeout alts!]]
            [clojurewerkz.urly.core :as urly]
            [attercop.enlive-utils :as enlive-utils]))


(defn normalize-href
  [referrer href]
  (urly/absolutize href referrer))


(defn extract-hrefs
  [html-nodes]
  (->> (enlive-utils/extract-hrefs html-nodes)
       (keep identity)
       (map s/trim)))


(defn allowed-domain?
  [allowed-domains url]
  (allowed-domains (.getHost (urly/url-like url))))


(defn fetch-url
  [{:keys [handle-status-codes user-agent]} url]
  (letfn [(allow? [status]
            (or (and (>= status 200)
                     (< status 300))
                (handle-status-codes status)))]
    @(http/get url
               {:user-agent user-agent}
               (fn [{:keys [status body]}]
                 (if (allow? status)
                   {:url url :html body :status status}
                   ;; todo! do proper logging here
                   (println (format "Error: [%s] %s" status url)))))))


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


(let [default-config {:max-wait 5000
                      :handle-status-codes #{}}]
  (defn start
    [{:keys [start-urls allowed-domains rules pipeline max-wait]
      :as config}]
    (let [config (merge default-config config)
          ch-urls (chan)
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
              (thread (if-let [resp (fetch-url config url)]
                        (>!! ch-resp resp)))
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
                             (->> (extract-hrefs html-nodes)
                                  (map (partial normalize-href url))
                                  (remove skip?)))]
                 (doseq [link links]
                   (go (>! ch-urls link)))
                 (when-let [result (scrape resp)]
                   (pipe result)))
               (recur))))
       ch-urls])))


(defn run
  [config]
  (let [[ch-main ch-urls] (start config)]
    ;; TODO! catch sigint and sigterm here and close ch-urls. That's
    ;; why start returns 2 channels, we wait on ch-main, close ch-urls
    ;; when sigint or sigterm are intercepted.
    (<!! ch-main)))
