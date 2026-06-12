(ns dscloj.test-config-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(defn- read-tests-edn []
  (edn/read-string {:readers {'kaocha/v1 identity}}
                   (slurp "tests.edn")))

(defn- make-target-body
  "Return the Makefile body lines for `target` up to the next non-indented target."
  [makefile target]
  (let [lines (str/split-lines makefile)
        target-line (str target ":")]
    (->> lines
         (drop-while #(not= target-line %))
         rest
         (take-while #(or (str/blank? %)
                          (str/starts-with? % "\t")
                          (str/starts-with? % " ")))
         (str/join "\n"))))

(deftest makefile-test-targets-use-explicit-suites
  (let [makefile (slurp "Makefile")]
    (testing "default and CI test targets run only unit tests"
      (is (str/includes? (make-target-body makefile "test")
                         "clojure -M:test -m kaocha.runner unit"))
      (is (str/includes? (make-target-body makefile "test-ci")
                         "clojure -M:test -m kaocha.runner unit")))

    (testing "integration target runs only the integration suite with OpenRouter wording"
      (is (str/includes? makefile "test-ci:"))
      (is (str/includes? (make-target-body makefile "test-integration")
                         "clojure -M:test -m kaocha.runner integration"))
      (is (str/includes? makefile "OPENROUTER_API_KEY")))))

(deftest kaocha-suites-stay-separated
  (let [suites (:tests (read-tests-edn))
        by-id (into {} (map (juxt :id identity) suites))]
    (is (= [:integration] (get-in by-id [:unit :skip-meta])))
    (is (= [:integration] (get-in by-id [:integration :focus-meta])))))

(deftest github-pr-integration-workflow-uses-openrouter
  (let [workflow (slurp ".github/workflows/integration.yml")]
    (is (str/includes? workflow "pull_request:"))
    (is (str/includes? workflow "branches: [ main, master, develop ]"))
    (is (str/includes? workflow "OPENROUTER_API_KEY: ${{ secrets.OPENROUTER_API_KEY }}"))
    (is (str/includes? workflow "OPENROUTER_MODEL:"))
    (is (str/includes? workflow "make test-integration"))))

(deftest live-integration-tests-use-openrouter-key
  (let [integration-test (slurp "test/dscloj/integration_test.clj")]
    (is (str/includes? integration-test "OPENROUTER_API_KEY"))
    (is (str/includes? integration-test "OPENROUTER_API_KEY is required in CI"))
    (is (not (str/includes? integration-test "OPENAI_API_KEY")))))
