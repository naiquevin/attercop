# attercop

A web scraping library inspired by Scrapy using core.async

## Warnings

* Mainly an exercise to learn core.async
* Still a WIP

## Usage

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

For a working example, see `attercop.spider-test` in `tests/`. It runs as a core.test test suite but can be run separately in a repl as well.

More examples coming soon.


## License

Copyright © 2014 [Vineet Naik](http://naiquevin.github.io/)

Distributed under the Eclipse Public License, the same as Clojure.
