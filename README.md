# attercop

A web scraping library inspired by Scrapy using core.async

## Warning

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

A running example is included in attercop.core that can be run as follows,

* First start serving a test site in another terminal

```bash
$ cd resources/testsite
$ python -m SimpleHTTPServer 5000
```

* Then run following in a repl

```clojure
attercop.core> (-main)
```

More examples coming soon


## License

Copyright © 2014 [Vineet Naik](http://naiquevin.github.io/)

Distributed under the Eclipse Public License, the same as Clojure.
