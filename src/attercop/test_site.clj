(ns attercop.test-site
  (:require [org.httpkit.server :refer [run-server]]
            [net.cgrand.enlive-html :as html]))


(defn- route->path
  [route]
  (format "/%s.html" (name route)))


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
  (html/clone-for [{:keys [text href]} links]
                  [:li :a] (html/content text)
                  [:li :a] (html/set-attr :href href))

  [:ul] (when (seq links) identity))


(defn render-template
  [t]
  (apply str t))


(defn sitemap->handler
  "Takes a sitemap which is a graph of links on the site represented
  as map nodes and vector of nodes and returns a function that can be
  passed as the handler to org.httpkit.server/run-server.

  The keys of the sitemap must be keywords. The values must be vectors
  of either,

    * keywords: If the node is represented as a keyword eg. :foo, then
  the title and url will be derived as \"Foo\" and \"/foo\"
  respectively and the status will be 200.

    * maps with keys text, href and status: In this case, text is a
  mandatory field to be specified + either status or href need to be
  specified. The remaining field is derived from the other two.

  eg. {:text \"Foo\" :status 404} => \"/status/404 -> 404 Not found
      {:text \"Foo\" :href \"/foo\" => \"/foo/ -> 200 OK

  Furthermore, a node that's found in any of the value vectors need
  not be specified as the key in the map.
  "
  [sitemap]
  (let [routes (mapcat (fn [[x ys]]
                         (conj (filter keyword? ys) x))
                       sitemap)
        routes (set routes)
        resp-status (fn [status msg]
                      {:status status
                       :headers {"Content-Type" "text/html"}
                       :body (format "%s %s" status msg)})
        resp-page (fn [route]
                    (let [title (route->title route)
                          links (map normalize-link (sitemap route))]
                      {:status 200
                       :headers {"Content-Type" "text/html"}
                       :body (render-template (page-template title links))}))]
    (fn [{:keys [uri] :as req}]
      (let [route (path->route uri)]
        (if (routes route)
          (resp-page route)
          (resp-status 404 "Not found"))))))


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
