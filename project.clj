(defproject purple "0.1.0-SNAPSHOT"
  :description "Purple"
  :url "http://www.purpleapp.com"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [compojure "1.1.8"]
                 [ring/ring-json "0.1.2"]
                 [c3p0/c3p0 "0.9.1.2"] ;; db connection pooling
                 [org.clojure/java.jdbc "0.2.3"]
                 [mysql/mysql-connector-java "5.1.18"]
                 [cheshire "5.4.0"] ;; json and related utilities
                 [clj-http "1.0.0"]
                 [crypto-password "0.1.3"]
                 [com.draines/postal "1.11.3"]
                 [gapi "1.0.1"]
                 [stuarth/clj-oauth2 "0.3.2"]
                 [enlive "1.1.5"]]
  ;; :pedantic? :warn
  :plugins [[lein-ring "0.8.13"]
            [lein-beanstalk "0.2.7"]]
  :ring {:handler purple.handler/app
         :auto-reload? true
         ;; :auto-refresh? true
         :reload-paths ["src"]}
  :profiles {:dev {:dependencies [[javax.servlet/servlet-api "2.5"]
                                  [ring-mock "0.1.5"]]}}
  :aot [purple.fb.FacebookGraphException
        ;; clj_oauth2.OAuth2Exception
        ]
  :aws {:beanstalk {:s3-bucket "leinbeanstalkpurple"
                    :region "us-west-2"}})
