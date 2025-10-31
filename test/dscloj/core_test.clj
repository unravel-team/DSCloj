(ns dscloj.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [dscloj.core :as dscloj]
            [malli.core :as m]))

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

;; =============================================================================
;; Malli Schema Tests
;; =============================================================================

(deftest malli-type->str-test
  (testing "Convert Malli keyword types to string types"
    (is (= "str" (dscloj/malli-type->str :string)))
    (is (= "int" (dscloj/malli-type->str :int)))
    (is (= "float" (dscloj/malli-type->str :double)))
    (is (= "float" (dscloj/malli-type->str :float)))
    (is (= "bool" (dscloj/malli-type->str :boolean))))
  
  (testing "Convert Malli predicate symbols to string types"
    (is (= "str" (dscloj/malli-type->str 'string?)))
    (is (= "int" (dscloj/malli-type->str 'int?)))
    (is (= "float" (dscloj/malli-type->str 'double?)))
    (is (= "bool" (dscloj/malli-type->str 'boolean?))))
  
  (testing "Fallback for unknown types"
    (is (= "str" (dscloj/malli-type->str :unknown)))))

(deftest malli-schema->field-test
  (testing "Convert simple Malli property to field"
    (let [field (dscloj/malli-schema->field :name :string)]
      (is (= :name (:name field)))
      (is (= "str" (:type field)))
      (is (string? (:description field)))))
  
  (testing "Convert Malli property with description"
    (let [field (dscloj/malli-schema->field :question [:string {:description "The question to answer"}])]
      (is (= :question (:name field)))
      (is (= "str" (:type field)))
      (is (= "The question to answer" (:description field)))))
  
  (testing "Convert Malli boolean property"
    (let [field (dscloj/malli-schema->field :is_valid [:boolean {:description "Is it valid?"}])]
      (is (= :is_valid (:name field)))
      (is (= "bool" (:type field)))
      (is (= "Is it valid?" (:description field))))))

(deftest malli-schema->fields-test
  (testing "Convert Malli map schema to fields vector"
    (let [schema [:map
                  [:question [:string {:description "The question"}]]
                  [:context [:string {:description "Context information"}]]]
          fields (dscloj/malli-schema->fields schema)]
      (is (= 2 (count fields)))
      (is (= :question (:name (first fields))))
      (is (= "str" (:type (first fields))))
      (is (= :context (:name (second fields))))))
  
  (testing "Handle nil schema"
    (is (nil? (dscloj/malli-schema->fields nil)))))

(deftest normalize-module-test
  (testing "Module with field vectors remains unchanged"
    (let [module {:inputs [{:name :question :type "str" :description "Question"}]
                  :outputs [{:name :answer :type "str" :description "Answer"}]}
          normalized (dscloj/normalize-module module)]
      (is (= (:inputs module) (:inputs normalized)))
      (is (= (:outputs module) (:outputs normalized)))))
  
  (testing "Module with Malli schemas gets converted to field vectors"
    (let [module {:input-schema [:map
                                 [:question [:string {:description "The question"}]]]
                  :output-schema [:map
                                  [:answer [:string {:description "The answer"}]]]}
          normalized (dscloj/normalize-module module)]
      (is (vector? (:inputs normalized)))
      (is (= 1 (count (:inputs normalized))))
      (is (= :question (:name (first (:inputs normalized)))))
      (is (= "str" (:type (first (:inputs normalized)))))
      (is (vector? (:outputs normalized)))
      (is (= 1 (count (:outputs normalized))))
      (is (= :answer (:name (first (:outputs normalized)))))))
  
  (testing "Field vectors take precedence over schemas"
    (let [module {:inputs [{:name :direct :type "str" :description "Direct input"}]
                  :input-schema [:map [:schema [:string {:description "Schema input"}]]]
                  :outputs [{:name :direct-out :type "str" :description "Direct output"}]
                  :output-schema [:map [:schema-out [:string {:description "Schema output"}]]]}
          normalized (dscloj/normalize-module module)]
      (is (= :direct (:name (first (:inputs normalized)))))
      (is (= :direct-out (:name (first (:outputs normalized))))))))

(deftest validate-input-test
  (testing "Valid input passes validation"
    (let [module {:input-schema [:map
                                 [:question :string]
                                 [:age :int]]}
          input {:question "What is 2+2?" :age 25}]
      (is (= input (dscloj/validate-input module input)))))
  
  (testing "Invalid input throws exception"
    (let [module {:input-schema [:map
                                 [:question :string]
                                 [:age :int]]}
          input {:question "What is 2+2?" :age "not-a-number"}]
      (is (thrown? Exception (dscloj/validate-input module input)))))
  
  (testing "Missing schema skips validation"
    (let [module {}
          input {:anything "goes"}]
      (is (= input (dscloj/validate-input module input))))))

(deftest validate-output-test
  (testing "Valid output passes validation"
    (let [module {:output-schema [:map
                                  [:answer :string]
                                  [:confidence :double]]}
          output {:answer "4" :confidence 0.95}]
      (is (= output (dscloj/validate-output module output)))))
  
  (testing "Invalid output throws exception"
    (let [module {:output-schema [:map
                                  [:answer :string]
                                  [:is_valid :boolean]]}
          output {:answer "Yes" :is_valid "not-a-boolean"}]
      (is (thrown? Exception (dscloj/validate-output module output)))))
  
  (testing "Missing schema skips validation"
    (let [module {}
          output {:anything "goes"}]
      (is (= output (dscloj/validate-output module output))))))

(deftest malli-module-prompt-test
  (testing "Generate prompt from Malli schema-based module"
    (let [module {:input-schema [:map
                                 [:question [:string {:description "The question to answer"}]]]
                  :output-schema [:map
                                  [:answer [:string {:description "The answer"}]]]
                  :instructions "Answer accurately."}
          prompt (dscloj/module->prompt module)]
      (is (string? prompt))
      (is (re-find #"Your input fields are:" prompt))
      (is (re-find #"Your output fields are:" prompt))
      (is (re-find #"question" prompt))
      (is (re-find #"answer" prompt))
      (is (re-find #"Answer accurately\." prompt))))
  
  (testing "Malli module with multiple fields"
    (let [module {:input-schema [:map
                                 [:text [:string {:description "Input text"}]]
                                 [:count [:int {:description "Number of items"}]]]
                  :output-schema [:map
                                  [:result [:string {:description "Result"}]]
                                  [:is_valid [:boolean {:description "Is valid?"}]]
                                  [:score [:double {:description "Score"}]]]}
          normalized (dscloj/normalize-module module)
          prompt (dscloj/module->prompt module)]
      ;; Check that fields are present in the prompt
      (is (re-find #"text" prompt))
      (is (re-find #"count" prompt))
      (is (re-find #"result" prompt))
      (is (re-find #"is_valid" prompt))
      (is (re-find #"score" prompt))
      ;; Check that normalized module has correct structure
      (is (= 2 (count (:inputs normalized))))
      (is (= 3 (count (:outputs normalized))))
      ;; Check field names were properly extracted
      (let [text-field (first (:inputs normalized))
            count-field (second (:inputs normalized))
            result-field (first (:outputs normalized))
            is-valid-field (second (:outputs normalized))
            score-field (nth (:outputs normalized) 2)]
        (is (= :text (:name text-field)))
        (is (= :count (:name count-field)))
        (is (= :result (:name result-field)))
        (is (= :is_valid (:name is-valid-field)))
        (is (= :score (:name score-field)))))))
