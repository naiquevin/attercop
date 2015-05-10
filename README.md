# Attercop

A web scraping library inspired by Scrapy using core.async.

## Warning

I started this project mainly as an exercise for learning
core.async. I have used scrapy before but it's a big project and
attercop mostly covers only those features of scrapy that I have used
myself. Not recommended for production use yet.

## Dependencies

* [core.async](https://github.com/clojure/core.async)
* [http-kit](http://www.http-kit.org/)
* [enlive](https://github.com/cgrand/enlive)
* [urly](https://github.com/michaelklishin/urly)
* [timbre](https://github.com/ptaoussanis/timbre)
* [slugger](https://github.com/pelle/slugger)

## A pseudo example

```clojure
(let [config {:name "test-spider"
              :allowed-domains #{"localhost" "127.0.0.1"}
              :start-urls ["http://127.0.0.1:5000/"]
              :rules [[#"[a-zA-Z]+.html$" {:scrape (fn [x] ..)
                                           :follow true}]
                      [#"\w+-\d+.html" {:scrape (fn [x] ..)
                                        :follow true}]
                      [:default {:scrape nil :follow false}]]
              :pipeline [(fn [x] ..)
                         prn]
              :max-wait 5000}]
  (attercop.spider/run config))
```

See `attercop.examples.dmoz-lisp` for a working example. It crawls
pages on the dmoz directory that are related to lisp based functional
programming languages and scraps the links to resources for each
language. It can be run as follows,

```bash
$ lein trampoline run -m attercop.examples.dmoz_lisp
```

## Spider config

List of the keys that can be specified in the spider config

* **:name** `required` `string`

  A human readable name for the spider.

* **:allowed-domains** `required` `set`

  Only URLs of the these domains will be considered for scraping and
  crawling.

* **:start-urls** `required` `seq`

  The spider will start crawling/scraping with these URLs.

* **:rules** `required` `seq`

  This is the most important configuration which is used to define
  what exactly is to be done for URLs matching a particular
  pattern. It's a sequence of multiple rules where each rule is a
  vector of two elements -

  1. A regex for matching the URL
  2. A map with the fields `:scrape` and `:follow`

  The value for `:scrape` can either be a function or `nil`. If a
  function is provided, it will be used to scrape the page and it
  should return a sequence. Even if only 1 item is to be scraped off
  the page, it must be wrapped in a single element seq. If `:scrape`
  is nil, then the page will not be scraped.

  `:follow` on the other hand can take 3 kinds of values

   1. `true`: the page will be followed ie. URLs on the page (that are
      of the allowed domains) will be queued for crawling)
   2. `false` or `nil`: the page will not be followed
   3. a function: if a function is provided, it is expected to return
      a sequence of urls to crawl.

   The function values for both `:scrape` and `:follow` will be called
   with the response map having keys `:url`, `:status`, `:html` and
   `:html-nodes` (these are enlive nodes so enlive functions can be
   directly called on them).

   Besides the above type of rules, there is another special default
   rule which is recommended to be added as the last rule. It has
   following structure,

   [:default {:scrape nil :follow false}]

   This will make sure that the URLs that don't match with any of the
   previous rules will be ignored (both :scrape and :follow falsy). If
   you forget to add this, then the spider will ensure a no-op default
   rule is added at the end of the rules sequence.

* **:pipeline** `required` `seq`

   A seq of functions which will be called in order for every scraped
   item. The scraped item will be passed to the functions and they
   should return a (may be) modified version of the item which will be
   passed to the next function in seq. The result of the last function
   in the seq will be ignored.

   Typically you would have functions that would either process the
   item data or store it in some kind of persistent storage in the
   pipeline.

* **:max-wait** `optional` `number` `default: 5000`

   The spider will wait for these many ms for the HTTP requests to
   complete. Default is 5000ms.

* **:rate-limit** `optional` `vector` `default: [5 3000]`

   The spider will use this config to rate limit the HTTP requests. A
   value of type `[m, n]` means a maximum of `m` requests will be made
   in `n` ms. The default is 5 requests in 3000ms.

* **:handle-status-codes** `optional` `set` `default: #{}`

   Additional status codes to be handled by the scraper besides the
   standard valid ones ie. 2xx.

* **:graceful-shutdown?** `optional` `boolean|number` `default: 5000`

   If non-falsy, the spider will be gracefully shutdown ie. all queued
   URLs will be processed before stopping. Truthy values may be
   boolean or a number representing time to wait in milliseconds. If
   boolean, the timeout will be same as max-wait.

## Running tests

```bash
$ lein test
```

## License

Copyright © 2015 [Vineet Naik](http://naiquevin.github.io/)

Distributed under the Eclipse Public License, the same as Clojure.
