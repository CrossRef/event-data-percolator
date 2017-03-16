(defproject event-data-percolator "0.1.13"
  :description "Event Data Percolator"
  :url "http://eventdata.crossref.org/"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.cache "0.6.5"]
                 [org.clojure/core.async "0.2.395"]
                 [event-data-common "0.1.20"]
                 [enlive "1.1.6"]
                 [org.clojure/core.memoize "0.5.8"]
                 [commons-codec/commons-codec "1.10"]
                 [com.cemerick/url "0.1.1"]
                 [prismatic/schema "1.1.3"]
                 [clj-http "2.3.0"]
                 [http-kit.fake "0.2.1"]
                 [org.clojure/data.json "0.2.6"]
                 [crossref-util "0.1.10"]
                 [http-kit "2.1.18"]
                 [http-kit.fake "0.2.1"]
                 [liberator "0.14.1"]
                 [ring "1.5.0"]
                 [ring/ring-jetty-adapter "1.5.0"]
                 [ring/ring-servlet "1.5.0"]
                 [ring/ring-mock "0.3.0"]
                 [org.eclipse.jetty/jetty-server "9.4.0.M0"]
                 [overtone/at-at "1.2.0"]
                 [robert/bruce "0.8.0"]
                 [yogthos/config "0.8"]
                 [compojure "1.5.1"]
                 [crossref/heartbeat "0.1.2"]
                 [com.auth0/java-jwt "2.2.1"]
                 [clj-time "0.12.2"]
                 [redis.clients/jedis "2.8.0"]
                 [metosin/scjsv "0.4.0"]
                 [com.amazonaws/aws-java-sdk "1.11.61"]
                 [com.github.crawler-commons/crawler-commons "0.7"]
                 
                 ; Required for AWS, but not fetched.
                 [org.apache.httpcomponents/httpclient "4.5.3"] ; Reqired for TLS SNI.
                 [org.apache.commons/commons-io "1.3.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.apache.logging.log4j/log4j-core "2.6.2"]
                 [org.slf4j/slf4j-simple "1.7.21"]
]
  :main ^:skip-aot event-data-percolator.core
  :target-path "target/%s"
  :plugins [[jonase/eastwood "0.2.3"]
            [lein-cloverage "1.0.9"]]
  :test-selectors {:default (constantly true)
                   :unit :unit
                   :component :component
                   :integration :integration
                   :all (constantly true)}
  :profiles {:uberjar {:aot :all}
             :prod {:resource-paths ["config/prod"]}
             :dev  {:resource-paths ["config/dev"]}})
