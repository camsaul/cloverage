(ns com.mdelaurentis.coverage
  (:import [clojure.lang LineNumberingPushbackReader IObj]
           [java.io File InputStreamReader])
  (:use [clojure.java.io :only [reader writer copy]]
        [clojure.tools.cli :only [cli]]
        [com.mdelaurentis instrument])
  (:require [clojure.set :as set]
            [clojure.test :as test]
            [clojure.tools.logging :as log])

  (:gen-class))

(def ^:dynamic *covered*)

;; borrowed from duck-streams
(defmacro with-out-writer
  "Opens a writer on f, binds it to *out*, and evalutes body.
  Anything printed within body will be written to f."
  [f & body]
  `(with-open [stream# (writer ~f)]
     (binding [*out* stream#]
       ~@body)))

(defmacro with-coverage [libs & body]
  `(binding [*covered* (ref [])]
     (println "Capturing code coverage for" ~libs)
     (doseq [lib# ~libs]
       (instrument lib#))
     ~@body
     (gather-stats @*covered*)))

(defn cover [idx]
  "Mark the given file and line in as having been covered."
  (dosync 
   (if (contains? @*covered* idx)
     (alter *covered* assoc-in [idx :covered] true)
     (log/warn (str "Couldn't track coverage for form with index " idx ".")))))

(defmacro capture 
  "Eval the given form and record that the given line on the given
  files was run."
  [idx form]
  (let [text (with-out-str (prn form))]
    `(do 
       (cover ~idx)
       ~form)))

(defn add-form 
  "Adds a structure representing the given form to the *covered* vector."
  [form line-hint]
  (println "Adding form" form "at line" (:line (meta form)) "hint" line-hint)
  (let [file *instrumenting-file*
        line (if (:line (meta form)) (:line (meta form)) line-hint)
        form-info {:form (or (:original (meta form))
                             form)
                   :line line
                   :file file}]
  (binding [*print-meta* true]
    (prn "Parsed form" form)
    (prn "Adding" form-info)
    (newline))
    (dosync 
     (alter *covered* conj form-info)
     (dec (count @*covered*)))))

(defn track-coverage [line-hint form]
  #_(println "Track coverage called with" form)
  `(capture ~(add-form form line-hint) ~form))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Reporting

(defn- group-by-line [forms]
  (into (sorted-map) (group-by :line forms)))

(defn- postprocess-file [resource forms]
  (with-open [in (reader (resource-reader resource))]
    (let [forms-by-line (group-by-line forms)
          make-rec (fn [line text]
                     (map (partial merge {:text text :line line :file resource})
                          (forms-by-line line [{:line line}])))
          line-nums (next (iterate inc 0))
          lines (into [] (line-seq in))]
      (mapcat make-rec line-nums lines))))

(defn gather-stats [forms]
  (let [forms-by-file (group-by :file forms)]
    (mapcat (partial apply postprocess-file) forms-by-file)))

(defn line-stats [forms]
  (for [[line line-forms] (group-by-line forms)]
    {:line line
     :text (:text (first line-forms))
     :tooltip (apply str (interpose \: line-forms))
     :covered (every? :covered line-forms)
     :partial (some :covered line-forms)
     :instrumented  (some :form line-forms)
     :blank (empty? (:text (first line-forms)))}))

(defn file-stats [forms]
  (for [[file file-forms] (group-by :file forms)
        :let [lines (line-stats file-forms)]]
    {:file file
     :lines (count lines)
     :non-blank-lines (count (remove :blank lines))
     :instrumented-lines (count (filter :instrumented lines))
     :covered-lines (count (filter :covered lines))}))

(defn stats-report [file cov]
  (.mkdirs (.getParentFile file))
  (with-open [outf (writer file)] 
    (binding [*out* outf]
      (printf "Lines Non-Blank Instrumented Covered%n") 
      (doseq [file-info (file-stats cov)]
        (apply printf "%5d %9d %7d %10d %s%n"
               (map file-info [:lines :non-blank-lines :instrumented-lines
                               :covered-lines :file]))))))

(defn report [out-dir forms]
  (stats-report (File. out-dir "coverage.txt") forms)
  (doseq [[file file-forms] (group-by :file forms)
          :when file]
    (println "Reporting on" file)
    (let [file (File. out-dir file)]
      (.mkdirs (.getParentFile file))
      (with-out-writer file
        (doseq [line (line-stats file-forms)]
          (let [prefix (cond (:blank line)   " "
                             (:covered line) "+"
                             (:instrumented line) "-"
                             :else           "?")]
            (println prefix (:text line))))))))

(defn replace-spaces [s]
  (.replace s " " "&nbsp;"))

(defn html-report [out-dir forms]
  (copy (resource-reader "coverage.css") (File. out-dir "coverage.css"))
  (stats-report (File. out-dir "coverage.txt") forms)
  (doseq [[rel-file file-forms] (group-by :file forms)]
    (let [file     (File. out-dir (str rel-file ".html"))
          rootpath (.relativize (.. file getParentFile toPath) (.toPath (File. out-dir)))
          ]
      (.mkdirs (.getParentFile file))
      (with-out-writer file
        (println "<html>")
        (println " <head>")
        (printf "  <link rel=\"stylesheet\" href=\"%s/coverage.css\"/>" rootpath)
        (println "  <title>" rel-file "</title>")
        (println " </head>")
        (println " <body>")
        (doseq [line (line-stats file-forms)]
          (let [cls (cond (:blank line) "blank"
                          (:covered line) "covered"
                          (:partial line) "partial"
                          (:instrumented line) "not-covered"
                          :else            "not-tracked")]
            (printf "<span class=\"%s\">%03d%s</span><br/>%n" cls (:line line) (replace-spaces (:text line "&nbsp;")))))
        (println " </body>")
        (println "</html>")))))

(defn parse-args [args]
  (cli args ["-o" "--output"]
            ["-t" "--[no-]text"]
            ["-h" "--[no-]html"]
            ["-r" "--[no-]raw"]))

(defn -main
  "Produce test coverage report for some namespaces"
  [& args]
  (let [[opts, namespaces] (parse-args args)
        output       (:output opts)
        text?        (:text opts)
        html?        (:html opts)
        raw?         (:raw opts)
        ]
    (binding [*covered* (ref [])
              *ns* (find-ns 'com.mdelaurentis.coverage)]
      ;; Load all the namespaces, so that any requires within them
      ;; will not re-load the ns.
      (apply require (map symbol namespaces))
      (doseq [namespace (map symbol namespaces)]
        (instrument track-coverage namespace))
      (apply test/run-tests (map symbol namespaces))
      (when output
        (.mkdir (File. output))
        (let [stats (gather-stats @*covered*)]
          (when text?
            (report output stats))
          (when html?
            (html-report output stats))
          (when raw?
            (with-out-writer (File. (File. output) "covered.clj")
              (clojure.pprint/pprint @*covered*))
            (with-out-writer (File. (File. output) "coverage.clj")
              (clojure.pprint/pprint stats))))))))
