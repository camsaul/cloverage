(ns cloverage.instrument-test
  (:require [clojure.test :as t]
            [cloverage.instrument :as inst]
            [riddley.walk :as rw]))

(def simple-forms
  "Simple forms that do not require macroexpansion and have no side effects."
  [1
   "A STRING"
   ''("a" "simple" "list")
   [1 2 'vector 3 4]
   {:simple :map :here 1}
   #{:sets :should :work}
   '(do :expression)])

(t/deftest wrap-preserves-value
  (doseq [simple-expr simple-forms]
    (t/is (= simple-expr (rw/macroexpand-all (inst/wrap #'inst/no-instr 0 simple-expr))))
    (t/is (= (eval simple-expr) (eval (inst/wrap #'inst/nop 0 simple-expr))))))

(t/deftest correctly-resolves-macro-symbols
  ;; simply ensure that instrumentation succeeds without errors
  (t/is (inst/instrument #'inst/no-instr 'cloverage.sample.read-eval-sample)))

(defn- form-type-
  "Provide a default empty env to form-type, purely for easier testing."
  ([f] (form-type- f nil))
  ([f e] (inst/form-type f e)))

(defprotocol Protocol
  (method [this]))

(defrecord Record [foo]
  Protocol
  (method [_] foo))

(t/deftest test-form-type
  (t/is (= :atomic (form-type- 1)))
  (t/is (= :atomic (form-type- "foo")))
  (t/is (= :atomic (form-type- 'bar)))
  (t/is (= :coll (form-type- [1 2 3 4])))
  (t/is (= :coll (form-type- {1 2 3 4})))
  (t/is (= :coll (form-type- #{1 2 3 4})))
  (t/is (= :coll (form-type- (Record. 1))))
  (t/is (= :list (form-type- '(+ 1 2))))
  (t/is (= :do (form-type- '(do 1 2 3))))
  (t/is (= :list (form-type- '(loop 1 2 3)
                             {'loop 'hoop})))) ;fake a local binding

(t/deftest do-wrap-for-record-returns-record
  (t/is (= 1 (method (eval (inst/wrap #'inst/nop 0 (Record. 1)))))))

(t/deftest do-wrap-for-record-func-key-returns-func
  (t/is (= 1 ((method (eval (inst/wrap #'inst/nop 0 (Record. (fn [] 1)))))))))

(t/deftest preserves-fn-conditions
  (let [pre-fn (eval (inst/wrap #'inst/nop 0
                                '(fn [n] {:pre [(> n 0) (even? n)]} n)))]
    (t/is (thrown? AssertionError (pre-fn -1)))
    (t/is (thrown? AssertionError (pre-fn 1)))
    (t/is (= 2 (pre-fn 2))))
  (let [post-fn (eval (inst/wrap #'inst/nop 0
                                 '(fn [n] {:post [(> % 3) (even? %)]} n)))]
    (t/is (thrown? AssertionError (post-fn 1)))
    (t/is (thrown? AssertionError (post-fn 5)))
    (t/is (= 4 (post-fn 4))))
  ;; XXX: side effect, but need to test defn since we special case it
  (let [both-defn (eval (inst/wrap #'inst/nop 0
                                   '(defn both-defn [n]
                                      {:pre [(> n -1)] :post [(> n 0)]}
                                      n)))]
    (t/is (thrown? AssertionError (both-defn 0)))
    (t/is (thrown? AssertionError (both-defn -1)))
    (t/is (= 1 (both-defn 1)))))

(t/deftest test-exclude-calls
  (let [form    '(doseq [_ 100])
        wrapped (inst/do-wrap #'inst/nop 42 form {})]
    (t/is (not= form wrapped))
    (binding [inst/*exclude-calls* #{'clojure.core/doseq}]
      (let [wrapped (inst/do-wrap #'inst/nop 42 form {})]
        (t/is (= form wrapped))))))

(defmacro ^:private on-line [line-number form]
  `(with-meta ~form {:line ~line-number}))

(t/deftest test-wrap-let-form
  (t/testing "let should recurisvely wrap its forms"
    (let [form (list 'let ['x (list 'let ['y (on-line 3 '(+ 1 2))]
                                    'y)]
                     (on-line 4 '(+ x 3)))]
      (t/is (= '(let [x (let [y ((cloverage.instrument/wrapm cloverage.instrument/no-instr 3 +)
                                 (cloverage.instrument/wrapm cloverage.instrument/no-instr 3 1)
                                 (cloverage.instrument/wrapm cloverage.instrument/no-instr 3 2))]
                          y)]
                  ((cloverage.instrument/wrapm cloverage.instrument/no-instr 4 +)
                   (cloverage.instrument/wrapm cloverage.instrument/no-instr 4 x)
                   (cloverage.instrument/wrapm cloverage.instrument/no-instr 4 3)))
               (inst/do-wrap #'inst/no-instr 1 form nil))))))

(t/deftest test-wrap-defrecord-methods
  (let [form    (list 'defrecord 'MyRecord []
                      'Protocol
                      (list 'method []
                            (with-meta '(do-something) {:line 1337})))
        wrapped (list 'defrecord 'MyRecord []
                      'Protocol
                      (list 'method []
                            (inst/wrap #'inst/no-instr 1337 '(do-something))))]
    (t/is (not= form wrapped))
    (t/is (= wrapped
             (inst/do-wrap #'inst/no-instr 0 form nil))
          "Lines inside defrecord methods should get wrapped.")))

(t/deftest test-wrap-deftype-methods
  ;; (deftype ...) expands to (let [] (deftype* ...))
  ;; ignore the let form & binding because we're only interested in how `deftype*` gets instrumented
  (let [form (nth
              (macroexpand-1
               (list 'deftype 'MyType []
                     'Protocol
                     (list 'method []
                           (with-meta '(do-something) {:line 1337}))))
              2)
        wrapped (nth
                 (macroexpand-1
                  (list 'deftype 'MyType []
                        'Protocol
                        (list 'method []
                              (inst/wrap #'inst/no-instr 1337 '(do-something)))))
                 2)]
    (t/is (= (first form) 'deftype*)) ; make sure we're actually looking at the right thing
    (t/is (not= form wrapped))
    (t/is (= wrapped
             (inst/do-wrap #'inst/no-instr 0 form nil))
          "Lines inside deftype methods should get wrapped.")))

(t/deftest test-deftype-defrecord-line-metadata
  ;; * If an individual line in a defrecord or deftype method body has ^:line metadata, we should use that (3 in the
  ;;   test below)
  ;;
  ;; * Failing that, if the entire (method [args*] ...) form has line number metadata, we should use that (2 in the
  ;;   test below)
  ;;
  ;; * Finally, we should fall back to using the line number passed in to `wrap-deftype-defrecord-methods`
  ;; * (presumably the line of the entire `defrecord`/`deftype` form) (1 in the test below)
  (let [form    (list
                 'defrecord 'MyRecord []
                 'Protocol
                 (-> (list 'method-with-meta []
                           (with-meta '(line-with-meta) {:line 3})
                           (with-meta '(line-without-meta) nil))
                     (with-meta {:line 2}))
                 (-> (list 'method-without-meta [] (with-meta '(line-without-meta) nil))
                     (with-meta nil)))
        wrapped (list 'defrecord 'MyRecord []
                      'Protocol
                      (list 'method-with-meta []
                            (inst/wrap #'inst/no-instr 3 '(line-with-meta))
                            (inst/wrap #'inst/no-instr 2 '(line-without-meta)))
                      (list 'method-without-meta []
                            (inst/wrap #'inst/no-instr 1 '(line-without-meta))))]
    (t/is (= wrapped
             (inst/do-wrap #'inst/no-instr 1 form nil))
          "Wrapped defrecord/deftype methods should use most-specific line number metadata available.")))
