(ns attercop.spider
  (:require [clojure.string :as s]
            [org.httpkit.client :as http]
            [clojure.core.async
             :refer [chan >! >!! <! <!! go thread
                     close! timeout alts!]]
            [clojurewerkz.urly.core :as urly]
            [taoensso.timbre :as timbre]
            [attercop.enlive-utils :as enlive-utils]))


(defn- normalize-href
  "Normalizes a href attribute so that it always returns an absolute
  URL. The referrer is used to absolutize the URL in case it's
  relative.

  eg.
      (normalize-href \"http://example.com\" \"/foo.html\")
      ;= http://example.com/foo.html
  "
  [referrer href]
  (urly/absolutize href referrer))


(defn- extract-hrefs
  [html-nodes]
  (->> (enlive-utils/extract-hrefs html-nodes)
       (keep identity)
       (map s/trim)))


(defn- url->domain
  [url]
  (.getHost (urly/url-like url)))


(defn- fetch-url
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
                   (timbre/info (format "Error: [%s] %s" status url)))))))


(defn- run-scrapers
  "Takes a sequence of rules and runs the scraper function on the
  response for the first rule that matches the url. The rest of the
  rules in the sequence if any are ignored. A ':default' rule always
  matches and is expected to be the last rule in the sequence."
  [rules {:keys [url] :as resp}]
  (when-let [rule (first rules)]
    (let [[check {scrape :scrape}] rule]
      (if (or (= check :default) (re-find check url))
        (when scrape
          (scrape resp))
        (recur (rest rules) resp)))))


(defn- follow-url?
  "Checks if the given url is to be followed as per the rules. The
  first rule that matches for the url is considered and the rest are
  ignored."
  [rules url]
  (when-let [rule (first rules)]
    (let [[check {follow :follow}] rule]
      (if (or (= check :default) (re-find check url))
        (boolean follow)
        (recur (rest rules) url)))))


(defn- skip-url?
  "Checks if the url can be skipped even if the rule matched for
  it. This is the case when :scrape and :follow for the rule are both
  nil."
  [rules url]
  (when-let [rule (first rules)]
    (let [[check {:keys [scrape follow]}] rule]
      (if (or (= check :default) (re-find check url))
        (not (or scrape follow))
        (recur (rest rules) url)))))


(defn- pipe-results
  "Takes a pipeline which is a sequence of functions transforming or
  doing something with the result and pipes the result through each of
  the functions in order."
  [pipeline results]
  (doseq [result results]
    (reduce (fn [acc f] (f acc)) result pipeline)))


(defn- init-throttle
  "Takes throttle config ie. a vector of 'max-hits' to be allowed in
  the given 'interval' and sets up throttling mechanism. It returns a
  core.async channel.

  How throttling works:

  A core.async channel is created with buffer size equal to max-hits
  and go block is initiated which periodically takes elements from the
  channel, waiting for some interval which is dynamically calculated
  if the buffer is getting filled too fast.

  The main thread does a blocking push to the channel everytime
  before making the HTTP request.
  "
  [[max-hits interval]]
  (let [ch-throttle (chan max-hits)
        wait (/ interval max-hits)]
    (go (loop []
          (let [begin-ts (System/currentTimeMillis)]
            (dotimes [i max-hits]
              (alts! [ch-throttle (timeout wait)]))
            (let [diff-ts (- (System/currentTimeMillis) begin-ts)]
              (if (< diff-ts interval)
                (Thread/sleep (- interval diff-ts)))))
          (recur)))
    ch-throttle))


(let [default-config {:max-wait 5000
                      :handle-status-codes #{}
                      :rate-limit [5 3000]}]
  (defn start
    "Starts the spider as per the config. Returns a vector of
  channels:

      1. a main channel that does the scraping. The caller function
  can wait for the scraping thread to exit by blocking on this channel.

      2. the urls channel. By closing this channel, the scraper can be
  gracefully shutdown.
    "
    [{:keys [start-urls allowed-domains rules
             pipeline max-wait rate-limit]
      :as config}]
    (let [config (merge default-config config)
          ch-urls (chan)
          ch-resp (chan)
          ch-throttle (init-throttle rate-limit)
          scrape (partial run-scrapers rules)
          start-url-set (set start-urls)
          visited-urls (atom #{})
          follow? (fn [url]
                    (or (start-url-set url)
                        (follow-url? rules url)))
          skip? (fn [url]
                  (or (@visited-urls url)
                      (not (allowed-domains (url->domain url)))
                      (skip-url? rules url)))
          pipe (partial pipe-results pipeline)]
      ;; put urls into the urls channel
      (go (doseq [url start-urls]
            (>! ch-urls url)))
      ;; take urls from urls channel and put into the response channel
      (go (loop []
            (when-let [url (first (alts! [ch-urls (timeout max-wait)]))]
              (>! ch-throttle :ok)
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
                 (when-let [results (scrape resp)]
                   (pipe results)))
               (recur))))
       ch-urls])))


(defn run
  "Runs the scraper as per the given config. Blocks until all urls are scraped.

  Config
  ------

    :name ; [str] human readable name for the scraper

    :allowed-domains ; [set] urls of these will only be considered.

    :start-urls ; [seq] scraping/crawling will start with these

    :rules ; [seq] A rule is a vector of two elements, 1. a regular
  expression or the keyword :default 2. a map with fields :scrape (fn)
  and :follow (bool)

    :pipeline ; [seq] functions transforming or doing something with
  the scraped results.

    :max-wait ; [integer] Timeout for the HTTP requests in ms.
  [Default: 5000]

    :rate-limit ; [vector] (Optional) eg. [m, i] which means, 'm'
  max-hits in 'i' ms [Default: [5 3000]]

    :handle-status-codes ; [set] (Optional) Additional status codes to
  be handled by the scraper besides the standard valid ones ie. 2xx.

    :graceful-shutdown? ; [bool|integer] (Optional) If non-falsy, the
  spider will be gracefully shutdown. Truthy values may be boolean or
  a number representing time to wait in milliseconds. If boolean, the
  timeout will be same as max-wait [default: 5000]
  "
  [{:keys [graceful-shutdown? max-wait]
    :as config
    :or {graceful-shutdown? 5000}}]
  (let [[ch-main ch-urls] (start config)]
    (when graceful-shutdown?
      (let [wait (if (number? graceful-shutdown?)
                   graceful-shutdown?
                   max-wait)]
        (.addShutdownHook (Runtime/getRuntime)
                          (Thread. (fn []
                                     (timbre/info "Shutting down gracefully")
                                     (close! ch-urls)
                                     (Thread/sleep wait))))))
    (<!! ch-main)))
