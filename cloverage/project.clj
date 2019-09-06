(defproject camsaul/cloverage "1.1.2"
  :description "Cam's fork of Cloverage; adds support for deftype forms."
  :url "https://www.github.com/camsaul/cloverage"
  :scm {:name "git"
          :dir  ".."
          :url  "https://www.github.com/camsaul/cloverage"
          :tag  "HEAD"}
  :vcs :git
  :main ^:skip-aot cloverage.coverage
  :aot [clojure.tools.reader]
  :license {:name "Eclipse Public License - v 1.0"
          :url "http://www.eclipse.org/legal/epl-v10.html"
          :distribution :repo
            :comments "same as Clojure"}

  :deploy-repositories [["clojars"
                         {:url           "https://clojars.org/repo"
                          :sign-releases false}]]
  :dependencies [[org.clojure/tools.reader "1.1.2"]
                    [org.clojure/tools.cli "0.3.5"]
                    [org.clojure/tools.logging "0.4.0"]
                    [org.clojure/data.xml "0.0.8"]
                    [org.clojure/data.json "0.2.6"]
                    [timofreiberg/bultitude "0.2.10"]
                    [riddley "0.1.14"]
                    [slingshot "0.12.2"]]
  :profiles {:dev {:aot ^:replace []
                   :dependencies [[org.clojure/clojure "1.8.0"]]
                   :plugins [[lein-cljfmt "0.5.7"]
                             [jonase/eastwood "0.2.5"]
                             [lein-kibit "0.1.6"]]
                    :eastwood {:exclude-linters [:no-ns-form-found]}
                    :global-vars {*warn-on-reflection* true}}
          :sample {:source-paths ["sample"]}
          :1.4    {:dependencies [[org.clojure/clojure "1.4.0"]]}
          :1.5    {:dependencies [[org.clojure/clojure "1.5.1"]]}
          :1.6    {:dependencies [[org.clojure/clojure "1.6.0"]]}
          :1.7    {:dependencies [[org.clojure/clojure "1.7.0"]]}
          :1.8    {:dependencies [[org.clojure/clojure "1.8.0"]]}
          :master {:dependencies [[org.clojure/clojure "1.9.0-master-SNAPSHOT"]]}}
  :aliases {"all" ["with-profile" "+1.4:+1.5:+1.6:+1.7:+1.8:+master"]})
