(ns attercop.mock-site
  (:require [org.httpkit.server :refer [run-server]]
            [net.cgrand.enlive-html :as html]
            [slugger.core :as sc]))


(defn- route->path
  [route]
  (format "/%s.html" (name route)))


(defn- title->path
  [title]
  (-> title
      sc/->slug
      keyword
      route->path))


(defn- path->route
  [path]
  (let [[_ r] (re-find #"/(.+)\.html$" path)]
    (keyword r)))


(defn- route->title
  [route]
  (format "%s" (name route)))


(defn- normalize-link
  [link]
  (cond (keyword? link)
        {:text (route->title link)
         :href (route->path link)}

        (map? link)
        (let [link (merge {:status 200} link)]
          (if (not (:href link))
            (assoc link :href (format "/status/%s"
                                   (:status link)))
            link))

        :else
        (throw Exception)))


(html/deftemplate page-template "templates/test-site-page.html"
  [title links]
  [:head :title] (html/content title)

  [:h1] (html/content title)

  [:ul [:li html/first-of-type]]
  (html/clone-for [{:keys [title href]} links]
                  [:li :a] (html/content title)
                  [:li :a] (html/set-attr :href href))

  [:ul] (when (seq links) identity))


(defn render-template
  [t]
  (apply str t))


(defn node->page
  [node]
  (cond (keyword? node)
        {:title (name node)
         :status 200
         :href (route->path node)}

        (map? node)
        (let [status (get node :status 200)
              href (get node :href (title->path (:title node)))]
          (assoc node :status status :href href))

        :else
        (throw (ex-info "Invalid sitemap node"))))


(defn page-on-domain?
  [{:keys [^String href] :as page}]
  (.startsWith href "/"))


(defn sitemap->routes
  [sitemap]
  (let [as-pair (fn [{:keys [href] :as page}]
                  [href page])
        first-levels (set (map route->path (keys sitemap)))]
    (into {} (mapcat (fn [[x ys]]
                       (let [subpages (map node->page ys)]
                         (conj (->> (filter (fn [{href :href}]
                                              (not (first-levels href)))
                                            subpages)
                                    (filter page-on-domain?)
                                    (map as-pair))
                               (-> (node->page x)
                                   (assoc :links subpages)
                                   as-pair))))
                     sitemap))))


(defn status->reason
  [status]
  (.getReasonPhrase (org.httpkit.HttpStatus/valueOf status)))


(defn sitemap->handler
  [sitemap]
  (let [routes (sitemap->routes sitemap)
        resp-status (fn [status]
                      {:status status
                       :headers {"Content-Type" "text/html"}
                       :body (format "%s %s"
                                     status
                                     (status->reason status))})
        resp-page (fn [{:keys [title status links] :as page}]
                    (let [tmpl (page-template title links)]
                      {:status status
                       :headers {"Content-Type" "text/html"}
                       :body (render-template tmpl)}))]
    (fn [{:keys [uri] :as req}]
      (if-let [{:keys [status] :as page} (routes uri)]
        (if (and (>= status 200) (< status 300))
          (resp-page page)
          (resp-status status))
        (resp-status 404)))))


(defonce server (atom nil))


(defn start-server
  "Start a test site server.

  Typical usage:

    (start-server (sitemap->handler sitemap) {:port 5000})

  Note that only one instance of server can be running at a time.
  "
  [app options]
  {:arglists (:arglists (meta #'run-server))}
  (reset! server (run-server app options)))


(defn stop-server
  "Stop the test server"
  []
  (when-not (nil? @server)
    (@server :timeout 100)
    (reset! server nil)))
