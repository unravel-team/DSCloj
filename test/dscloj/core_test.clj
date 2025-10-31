(ns dscloj.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [dscloj.core :as dscloj]
            [malli.core :as m]))

(deftest module->prompt-test
  (testing "Basic prompt generation with inputs and outputs"
    (let [module {:inputs [{:name :question
                           :spec :string
                           :description "The question to answer"}]
                  :outputs [{:name :answer
                            :spec :string
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
    (let [module {:inputs [{:name :text :spec :string :description "Input text"}]
                  :outputs [{:name :result :spec :string :description "Result"}]
                  :instructions "Process text.\n\nSTRICT RULES:\n1. Be concise\n2. Be accurate"}
          prompt (dscloj/module->prompt module)]
      (is (re-find #"STRICT RULES:" prompt))
      (is (re-find #"Be concise" prompt))
      (is (re-find #"Process text\." prompt))))

  (testing "Prompt generation with boolean output type"
    (let [module {:inputs [{:name :statement :spec :string :description "A statement"}]
                  :outputs [{:name :is_true :spec :boolean :description "Is it true?"}]
                  :instructions "Verify statements."}
          prompt (dscloj/module->prompt module)]
      (is (re-find #"True or False" prompt))))

  (testing "Prompt generation without instructions"
    (let [module {:inputs [{:name :x :spec :string :description "Input"}]
                  :outputs [{:name :y :spec :string :description "Output"}]}
          prompt (dscloj/module->prompt module)]
      (is (string? prompt))
      (is (not (re-find #"instructions" prompt))))))

(deftest parse-output-test
  (testing "Parse string output"
    (let [module {:outputs [{:name :answer :spec :string}]}
          response "[[ ## answer ## ]]\nParis"
          result (dscloj/parse-output response module)]
      (is (= "Paris" (:answer result)))))

  (testing "Parse boolean output - True"
    (let [module {:outputs [{:name :is_valid :spec :boolean}]}
          response "[[ ## is_valid ## ]]\nTrue"
          result (dscloj/parse-output response module)]
      (is (true? (:is_valid result)))))

  (testing "Parse boolean output - true (lowercase)"
    (let [module {:outputs [{:name :is_valid :spec :boolean}]}
          response "[[ ## is_valid ## ]]\ntrue"
          result (dscloj/parse-output response module)]
      (is (true? (:is_valid result)))))

  (testing "Parse boolean output - False"
    (let [module {:outputs [{:name :is_valid :spec :boolean}]}
          response "[[ ## is_valid ## ]]\nFalse"
          result (dscloj/parse-output response module)]
      (is (false? (:is_valid result)))))

  (testing "Parse integer output"
    (let [module {:outputs [{:name :count :spec :int}]}
          response "[[ ## count ## ]]\n42"
          result (dscloj/parse-output response module)]
      (is (= 42 (:count result)))))

  (testing "Parse float output"
    (let [module {:outputs [{:name :score :spec :double}]}
          response "[[ ## score ## ]]\n0.95"
          result (dscloj/parse-output response module)]
      (is (= 0.95 (:score result)))))

  (testing "Parse multiple outputs"
    (let [module {:outputs [{:name :answer :spec :string}
                           {:name :confidence :spec :double}
                           {:name :is_confident :spec :boolean}]}
          response (str "[[ ## answer ## ]]\nParis\n"
                       "[[ ## confidence ## ]]\n0.98\n"
                       "[[ ## is_confident ## ]]\nTrue")
          result (dscloj/parse-output response module)]
      (is (= "Paris" (:answer result)))
      (is (= 0.98 (:confidence result)))
      (is (true? (:is_confident result)))))

  (testing "Parse output with extra whitespace"
    (let [module {:outputs [{:name :answer :spec :string}]}
          response "[[ ## answer ## ]]\n  Paris  \n"
          result (dscloj/parse-output response module)]
      (is (= "Paris" (:answer result)))))

  (testing "Parse output with multiline content"
    (let [module {:outputs [{:name :explanation :spec :string}]}
          response "[[ ## explanation ## ]]\nLine 1\nLine 2\nLine 3"
          result (dscloj/parse-output response module)]
      (is (= "Line 1\nLine 2\nLine 3" (:explanation result)))))

  (testing "Invalid integer returns original string"
    (let [module {:outputs [{:name :count :spec :int}]}
          response "[[ ## count ## ]]\nnot-a-number"
          result (dscloj/parse-output response module)]
      (is (= "not-a-number" (:count result)))))

  (testing "Invalid float returns original string"
    (let [module {:outputs [{:name :score :spec :double}]}
          response "[[ ## score ## ]]\nnot-a-number"
          result (dscloj/parse-output response module)]
      (is (= "not-a-number" (:score result))))))

(deftest field-extraction-test
  (testing "Field name with underscores"
    (let [module {:outputs [{:name :my_field :spec :string}]}
          response "[[ ## my_field ## ]]\nValue"
          result (dscloj/parse-output response module)]
      (is (= "Value" (:my_field result)))))

  (testing "Field name with hyphens (keyword style)"
    (let [module {:outputs [{:name :my-field :spec :string}]}
          response "[[ ## my-field ## ]]\nValue"
          result (dscloj/parse-output response module)]
      (is (= "Value" (:my-field result))))))

;; =============================================================================
;; Malli Spec Tests
;; =============================================================================

(deftest spec->type-str-test
  (testing "Convert Malli specs to string types"
    (is (= "str" (dscloj/spec->type-str :string)))
    (is (= "int" (dscloj/spec->type-str :int)))
    (is (= "float" (dscloj/spec->type-str :double)))
    (is (= "float" (dscloj/spec->type-str :float)))
    (is (= "bool" (dscloj/spec->type-str :boolean))))
  
  (testing "Convert Malli predicate symbols to string types"
    (is (= "str" (dscloj/spec->type-str 'string?)))
    (is (= "int" (dscloj/spec->type-str 'int?)))
    (is (= "float" (dscloj/spec->type-str 'double?)))
    (is (= "bool" (dscloj/spec->type-str 'boolean?))))
  
  (testing "Convert vector specs"
    (is (= "str" (dscloj/spec->type-str [:string {:description "A string"}]))))
  
  (testing "Fallback for unknown types"
    (is (= "str" (dscloj/spec->type-str :unknown)))))

(deftest validation-test
  (testing "Valid field passes validation"
    (let [field {:name :age :spec :int :description "Age"}
          value 25]
      (is (= value (dscloj/validate-field field value)))))
  
  (testing "Invalid field throws exception"
    (let [field {:name :age :spec :int :description "Age"}
          value "not-a-number"]
      (is (thrown? Exception (dscloj/validate-field field value)))))
  
  (testing "Valid inputs pass validation"
    (let [fields [{:name :question :spec :string}
                  {:name :age :spec :int}]
          input {:question "What is 2+2?" :age 25}]
      (is (= input (dscloj/validate-inputs fields input)))))
  
  (testing "Invalid input throws exception"
    (let [fields [{:name :age :spec :int}]
          input {:age "not-a-number"}]
      (is (thrown? Exception (dscloj/validate-inputs fields input))))))

(deftest module-with-spec-test
  (testing "Module with Malli specs generates correct prompt"
    (let [module {:inputs [{:name :question
                           :spec [:string {:description "The question"}]}]
                  :outputs [{:name :answer
                            :spec [:string {:description "The answer"}]}]
                  :instructions "Answer accurately."}
          prompt (dscloj/module->prompt module)]
      (is (string? prompt))
      (is (re-find #"Your input fields are:" prompt))
      (is (re-find #"Your output fields are:" prompt))
      (is (re-find #"question" prompt))
      (is (re-find #"answer" prompt))
      (is (re-find #"Answer accurately\." prompt))))
  
  (testing "Module with multiple field types"
    (let [module {:inputs [{:name :text :spec :string :description "Input text"}
                          {:name :count :spec :int :description "Number of items"}]
                  :outputs [{:name :result :spec :string :description "Result"}
                           {:name :is_valid :spec :boolean :description "Is valid?"}
                           {:name :score :spec :double :description "Score"}]}
          prompt (dscloj/module->prompt module)]
      ;; Check that fields are present in the prompt
      (is (re-find #"text" prompt))
      (is (re-find #"count" prompt))
      (is (re-find #"result" prompt))
      (is (re-find #"is_valid" prompt))
      (is (re-find #"score" prompt))
      ;; Check that types are correctly converted
      (is (re-find #"str" prompt))
      (is (re-find #"int" prompt))
      (is (re-find #"bool" prompt))
      (is (re-find #"float" prompt)))))
