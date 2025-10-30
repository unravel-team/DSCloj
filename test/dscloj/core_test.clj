(ns dscloj.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [dscloj.core :as dscloj]))

(deftest module->prompt-test
  (testing "Basic prompt generation with inputs and outputs"
    (let [module {:inputs [{:name :question
                           :type "str"
                           :description "The question to answer"}]
                  :outputs [{:name :answer
                            :type "str"
                            :description "The answer"}]
                  :instructions "Answer questions accurately."}
          prompt (dscloj/module->prompt module)]
      (is (string? prompt))
      (is (re-find #"Your input fields are:" prompt))
      (is (re-find #"Your output fields are:" prompt))
      (is (re-find #"question" prompt))
      (is (re-find #"answer" prompt))
      (is (re-find #"Answer questions accurately\." prompt))))

  (testing "Prompt generation with instructions including rules"
    (let [module {:inputs [{:name :text :type "str" :description "Input text"}]
                  :outputs [{:name :result :type "str" :description "Result"}]
                  :instructions "Process text.\n\nSTRICT RULES:\n1. Be concise\n2. Be accurate"}
          prompt (dscloj/module->prompt module)]
      (is (re-find #"STRICT RULES:" prompt))
      (is (re-find #"Be concise" prompt))
      (is (re-find #"Process text\." prompt))))

  (testing "Prompt generation with boolean output type"
    (let [module {:inputs [{:name :statement :type "str" :description "A statement"}]
                  :outputs [{:name :is_true :type "bool" :description "Is it true?"}]
                  :instructions "Verify statements."}
          prompt (dscloj/module->prompt module)]
      (is (re-find #"True or False" prompt))))

  (testing "Prompt generation without instructions"
    (let [module {:inputs [{:name :x :type "str" :description "Input"}]
                  :outputs [{:name :y :type "str" :description "Output"}]}
          prompt (dscloj/module->prompt module)]
      (is (string? prompt))
      (is (not (re-find #"instructions" prompt))))))

(deftest parse-output-test
  (testing "Parse string output"
    (let [module {:outputs [{:name :answer :type "str"}]}
          response "[[ ## answer ## ]]\nParis"
          result (dscloj/parse-output response module)]
      (is (= "Paris" (:answer result)))))

  (testing "Parse boolean output - True"
    (let [module {:outputs [{:name :is_valid :type "bool"}]}
          response "[[ ## is_valid ## ]]\nTrue"
          result (dscloj/parse-output response module)]
      (is (true? (:is_valid result)))))

  (testing "Parse boolean output - true (lowercase)"
    (let [module {:outputs [{:name :is_valid :type "bool"}]}
          response "[[ ## is_valid ## ]]\ntrue"
          result (dscloj/parse-output response module)]
      (is (true? (:is_valid result)))))

  (testing "Parse boolean output - False"
    (let [module {:outputs [{:name :is_valid :type "bool"}]}
          response "[[ ## is_valid ## ]]\nFalse"
          result (dscloj/parse-output response module)]
      (is (false? (:is_valid result)))))

  (testing "Parse integer output"
    (let [module {:outputs [{:name :count :type "int"}]}
          response "[[ ## count ## ]]\n42"
          result (dscloj/parse-output response module)]
      (is (= 42 (:count result)))))

  (testing "Parse float output"
    (let [module {:outputs [{:name :score :type "float"}]}
          response "[[ ## score ## ]]\n0.95"
          result (dscloj/parse-output response module)]
      (is (= 0.95 (:score result)))))

  (testing "Parse multiple outputs"
    (let [module {:outputs [{:name :answer :type "str"}
                           {:name :confidence :type "float"}
                           {:name :is_confident :type "bool"}]}
          response (str "[[ ## answer ## ]]\nParis\n"
                       "[[ ## confidence ## ]]\n0.98\n"
                       "[[ ## is_confident ## ]]\nTrue")
          result (dscloj/parse-output response module)]
      (is (= "Paris" (:answer result)))
      (is (= 0.98 (:confidence result)))
      (is (true? (:is_confident result)))))

  (testing "Parse output with extra whitespace"
    (let [module {:outputs [{:name :answer :type "str"}]}
          response "[[ ## answer ## ]]\n  Paris  \n"
          result (dscloj/parse-output response module)]
      (is (= "Paris" (:answer result)))))

  (testing "Parse output with multiline content"
    (let [module {:outputs [{:name :explanation :type "str"}]}
          response "[[ ## explanation ## ]]\nLine 1\nLine 2\nLine 3"
          result (dscloj/parse-output response module)]
      (is (= "Line 1\nLine 2\nLine 3" (:explanation result)))))

  (testing "Invalid integer returns original string"
    (let [module {:outputs [{:name :count :type "int"}]}
          response "[[ ## count ## ]]\nnot-a-number"
          result (dscloj/parse-output response module)]
      (is (= "not-a-number" (:count result)))))

  (testing "Invalid float returns original string"
    (let [module {:outputs [{:name :score :type "float"}]}
          response "[[ ## score ## ]]\nnot-a-number"
          result (dscloj/parse-output response module)]
      (is (= "not-a-number" (:score result))))))

(deftest field-extraction-test
  (testing "Field name with underscores"
    (let [module {:outputs [{:name :my_field :type "str"}]}
          response "[[ ## my_field ## ]]\nValue"
          result (dscloj/parse-output response module)]
      (is (= "Value" (:my_field result)))))

  (testing "Field name with hyphens (keyword style)"
    (let [module {:outputs [{:name :my-field :type "str"}]}
          response "[[ ## my-field ## ]]\nValue"
          result (dscloj/parse-output response module)]
      (is (= "Value" (:my-field result))))))
