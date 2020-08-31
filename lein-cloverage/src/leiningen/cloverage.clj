(ns leiningen.cloverage
  (:require [leiningen.core.eval :as eval]
            [leiningen.core.main :as main]
            [leiningen.core.project :as project])
  (:import (clojure.lang ExceptionInfo)))

(defn get-lib-version []
  (or (System/getenv "CLOVERAGE_VERSION") "RELEASE"))

(defn already-has-cloverage? [project]
  (seq (for [[id _version] (:dependencies project)
             :when (= id 'camsaul/cloverage)]
         true)))

(defn ^:pass-through-help cloverage
  "Run code coverage on the project.

  You can set the CLOVERAGE_VERSION environment variable to override what
  version of cloverage is used, but it's better to set it in :dependencies.

  Specify -o OUTPUTDIR for output directory, for other options run
  `lein cloverage --help`."
  [project & args]
  (let [project        (project/merge-profiles project [:leiningen/test :test])
        project        (if (already-has-cloverage? project)
                         project
                         (update-in project [:dependencies]
                                    conj ['camsaul/cloverage (get-lib-version)]))
        test-selectors (:test-selectors project)
        opts           (assoc (:cloverage project)
                              :src-ns-path (vec (:source-paths project))
                              :test-ns-path (vec (:test-paths project)))]
    (try
      (eval/eval-in-project project
                            `(let [decls#      (-> []
                                                   (.getClass)
                                                   (.getClassLoader)
                                                   (.getResources "data_readers.clj")
                                                   enumeration-seq)
                                   read-decls# (comp read-string slurp)
                                   readers#    (reduce merge {} (map read-decls# decls#))]
                               (binding [clojure.tools.reader/*data-readers* readers#]
                                 ;; test-selectors needs unquoted here to be read as functions
                                 (cloverage.coverage/run-project (assoc '~opts :test-selectors ~test-selectors) ~@args)))
                            '(require 'cloverage.coverage))
      (catch ExceptionInfo e
        (main/exit (:exit-code (ex-data e) 1))))))
