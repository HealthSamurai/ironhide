(ns ironhide.core-test
  (:require [ironhide.core :as tf :refer :all]
            [matcho.asserts :as matcho]
            [com.rpl.specter :as sp]
            [clojure.test :refer :all]))


(def update-name-shell
  #:ih{:direction [:form :form-2]

       :rules [{:form   [:name]
                :form-2 [:fullname]}]
       :data  {:form   {:name "Full Name"}
               :form-2 {:fullname "Old Name"}}})

(get-data update-name-shell)
;; => {:form {:name "Full Name"}, :form-2 {:fullname "Full Name"}}

(def create-name-shell
  #:ih{:direction [:form :form-2]

       :rules [{:form   [:name]
                :form-2 [:fullname]}]
       :data  {:form   {:name "Full Name"}}})

(get-data create-name-shell)
;; => {:form {:name "Full Name"}, :form-2 {:fullname "Full Name"}}

(def default-name-shell
  #:ih{:direction [:form :form-2]

       :values {:person/name "Name not provided by form"}
       :rules  [{:form     [:name]
                 :form-2   [:fullname]
                 :ih/value {:form-2 [:person/name]}}]
       :data   {:form   {}
                :form-2 {:fullname "Old Name"}}})

(get-data default-name-shell)
;; => {:form {}, :form-2 {:fullname "Name not provided by form"}}

(def create-and-update-phone-shell
  #:ih{:direction [:form :fhir]

       :rules [{:form [:phones [:*]]
                :fhir [:telecom [:* {:system "phone"}] :value]}]
       :data  {:form {:phones ["+1 111" "+2 222"]}
               :fhir {:telecom [{:system "phone"
                                 :use    "home"
                                 :value  "+3 333"}
                                {:system "email"
                                 :value  "test@example.com"}]}}})

(get-data create-and-update-phone-shell)
;; =>
;; {:form {:phones ["+1 111" "+2 222"]},
;;  :fhir {:telecom [{:system "phone", :use "home", :value "+1 111"}
;;                   {:system "email", :value "test@example.com"}
;;                   {:system "phone", :value "+2 222"}]}}


(def sight-name-shell
  #:ih{:direction [:form :fhir]

       :rules [{:form   [:name :ihs/str<->vector [0]]
                :fhir [:name [0] :given [0]]}]
       :data  {:form   {:name "Full Name"}}})

(get-data sight-name-shell)
;; => {:form {:name "Full Name"}, :fhir {:name [{:given ["Full"]}]}}

(def micro-name-shell
  #:ih{:direction [:fhir :form] ;; !!!

       :micros #:ihm {:name<->vector [:name {:ih/sight  :ihs/str<->vector
                                             :separator ", "}]}

       :rules [{:form [:ihm/name<->vector [0]]
                :fhir [:name [0] :given [0]]}
               {:form [:ihm/name<->vector [1]]
                :fhir [:name [0] :family]}]
       :data  {:form {:name "Full, Name"}
               :fhir {:name [{:given ["First"] :family "Family"}]}}})

(get-data micro-name-shell :form)
;; => {:name "First, Family"}

(deftest path->sppath-test
  (matcho/assert
   [[0 :v1]]
   (sp/select
    (path->sp-path [:a [:* {:a :b}] :k])
    {:a [{:a :b :k :v1} {:a :c :k :v2}]}))

  (matcho/assert
   {:name [{:given ["Ivan"]}]}
   (sp/setval
    (path->sp-path [:name [0 {:filter :sample}] :given [0]])
    "Ivan"
    nil))

  (matcho/assert
   [[{:key :value}]]
   (sp/setval
    (path->sp-path [[0] [0] :key])
    :value
    nil)))


(deftest extract-test
  (def nested-structure
    {:a {:b [{:c 1 :d 2}
             {:c 3 :d 4}]}})

  ;; (matcho/assert
  ;;  [[0 {:c 3 :d 4}]]
  ;;  (get-values
  ;;   nested-structure
  ;;   [:a :b [:* {:c #(> % 2)}]]))

  (matcho/assert
   [[0 4]]
   (tf/get-values
    nested-structure
    [:a :b [:* {:c 3}] :d]))

  (matcho/assert
   [[{:c 3 :d 4}]]
   (tf/get-values
    nested-structure
    [:a :b [1]]))

  (matcho/assert
   [[0 0 "test"] [0 1 :value]]
   (tf/get-values {:name [{:given ["test" :value]}]} [:name [:*] :given [:*]])))


(def ds1
  {:a [[:v1] [:v2 :v3 :v4]]})
(def ds2
  {:b [{:c [{:k :v11}] :d :t1}
       {:c [{:k :v22} {:k :v33}] :d :t1}
       {:c [{:k :v44} {:k :v55}] :d :t2}]})

(def rules
  [{:source [:a [:*] [:*]]
    :sink [:b [:* {:d :t1}] :c [:*] :k]}])

(defn transform [source sink rules & [[from to :as direction]]]
  (-> {:ih/rules rules}
      (assoc-in [:ih/data (if direction from :source)] source)
      (assoc-in [:ih/data (if direction to :sink)] sink)
      (assoc-in [:ih/direction] (or direction [:source :sink]))
      (get-data (if direction to :sink))))

(deftest nested-collection-transform-test
  (matcho/assert
   {:b [{:c [{:k :v1}] :d :t1}
        {:c [{:k :v2} {:k :v3}] :d :t1}
        {:c [{:k :v44} {:k :v55}] :d :t2}]}
   (transform ds1 ds2 rules)))

(def ds3
  {:b [{:c [{:k :v11}] :d :t1}
       {:c [{:k :v44} {:k :v55}] :d :t2}]})

(deftest nested-collection-with-create-transform-test
  (matcho/assert
   [[1 2] [3 :v5]]
   (transform
    [[1 2] [3]]
    [[:v1] [:v4 :v5]]
    [{:source [[:*] [:*]]
      :sink   [[:*] [:*]]}]
    [:source :sink]))

  (matcho/assert
   {:b [{:c [{:k :v1}] :d :t1}
        {:c [{:k :v44} {:k :v55}] :d :t2}
        {:c [{:k :v2} {:k :v3} {:k :v4}] :d :t1}]}
   (transform ds1 ds3 rules [:source :sink])))

(def ds4
  {:b [{:c [{:k :v11} {:k :v77}] :d :t1}
       {:c [{:k :v22} {:k :v33}] :d :t1}
       {:c [{:k :v44} {:k :v55}] :d :t2}]})


(def p1
  {:phones ["+1" "+2"]
   :homephone "+6"
   :workphone "+7"})

(def p2
  {:telecom
   [{:system "phone"
     :use    "home"
     :value  "+3"}
    {:system "fax"
     :value  "fax-number"}
    {:system "phone"
     :use    "work"
     :value  "+5"}]})

(def prules1
  [{:source [:phones [:*]]
    :sink [:telecom [:* {:system "phone"}] :value]}])

(def prules2
  [{:source [:homephone]
    :sink [:telecom [0 {:system "phone" :use "home"}] :value]}
   {:source [:workphone]
    :sink   [:telecom [0 {:system "phone" :use "work"}] :value]}])

(deftest phone-test
  (matcho/assert
   {:telecom [{:value "+1"} {} {:value "+2"}]}
   (transform p1 (update p2 :telecom pop) prules1 [:source :sink]))

  (matcho/assert
   {:telecom [{:value "+6"} {} {:value "+7"}]}
   (transform p1 p2 prules2 [:source :sink]))

  (matcho/assert
   {:telecom [{:value "+6"} {} {:value "+7"}]}
   (transform p1 (update-in p2 [:telecom] pop) prules2 [:source :sink])))


(deftest nested-collection-with-restriction-transform-test
  (matcho/assert
   {:b [{:c [{:k :v1} {:k :v77}] :d :t1}
        {:c [{:k :v2} {:k :v3}] :d :t1}
        {:c [{:k :v44} {:k :v55}] :d :t2}]}
   (transform ds1 ds4 rules [:source :sink]))

  (matcho/assert
   [[1 2 :v3] [3 :v5]]
   (transform
    [[1 2] [3]]
    [[:v1 :v2 :v3] [:v4 :v5]]
    [{:source [[:*] [:*]]
      :sink   [[:*] [:*]]}]
    [:source :sink])))

(deftest hl7-address

  (matcho/assert
   {:address
    [{:line       ["123 HAPPY AVE" "NOWHERE TOWN" "ALBUKERKA"]
      :city       "ALBUKERKA"
      :state      "CA"
      :postalCode "98000"
      :country    "USA"}]}
   (transform
    [{:address ["123 HAPPY AVE"
                "NOWHERE TOWN"
                "ALBUKERKA"
                "CA"
                "98000"
                "USA"]
      :name    "blabl"}]
    nil
    [{:hl7  [[:*] :address [0]]
      :fhir [:address [:*] :line [0]]}
     {:hl7  [[:*] :address [1]]
      :fhir [:address [:*] :line [1]]}
     {:hl7  [[:*] :address [2]]
      :fhir [:address [:*] :line [2]]}

     {:hl7  [[:*] :address [2]]
      :fhir [:address [:*] :city]}
     {:hl7  [[:*] :address [3]]
      :fhir [:address [:*] :state]}
     {:hl7  [[:*] :address [4]]
      :fhir [:address [:*] :postalCode]}
     {:hl7  [[:*] :address [5]]
      :fhir [:address [:*] :country]}]

    [:hl7 :fhir])))


(deftest form-fhir-transform-test
  (def sample-data
    {:allergies-doesPatientHaveAnyKnownAllergies true
     :allergies-knownAllergies                   [102002 8429000]
     :demographic-patientInfo-name               "Ivan Petrov"
     ;; :demographic-patientInfo-firstname          "Ivan"
     ;; :demographic-patientInfo-lastname           "Petrov"
     :demographic-patientInfo-mrn                "175-15-64"
     :demographic-patientInfo-gender             "Male"
     :demographic-patientInfo-dob                "2010-10-10"
     :demographic-patientInfo-age                17
     :demographics-patientInfo-cellPhoneNumber   "+12222222222"
     :demographics-patientInfo-email             "ivanpetrov@email.com"})

  (def form-fhir-rules
    [{:form [:allergies-knownAllergies [:*]]

      :fhir
      [:allergies
       [:*
        {:verificationStatus "confirmed",
         :resourceType       "AllergyIntolerance",
         :patient
         {:resourceType "Patient",
          :id           "a087966b-41c6-450b-a386-106bfaa1bb72"}}]
       :code]}

     {:form [:demographic-patientInfo-dob]
      :fhir [:birthDate]}

     {:form [:demographic-patientInfo-name {:ih/sight  :ihs/str<->vector
                                            :separator "e"} [0]]
      :fhir [:name [0] :given [0]]}

     {:form [:demographic-patientInfo-name {:ih/sight  :ihs/str<->vector
                                            :separator "e"} [1]]
      :fhir [:name [0] :family]}

     {:form [:demographics-patientInfo-cellPhoneNumber]
      :fhir [:telecom [0 {:system "phone"}] :value]}
     {:form [:demographics-patientInfo-email]
      :fhir [:telecom [0 {:system "email"}] :value]}

     {:form [:demographic-patientInfo-gender]
      :fhir [:gender]}
     ])

  (def fhir-bundle
    {:patient
     {:id           "a087966b-41c6-450b-a386-106bfaa1bb72",
      :resourceType "Patient",
      :name         [{:given ["Ivan"], :family "Petrov"}],
      :gender       "male",
      :birthDate    "2010-10-10",
      :telecom
      [{:system "phone", :value "+12222222222"}
       {:system "email", :value "ivanpetrov@email.com"}]}

     :allergies
     [{:verificationStatus "confirmed",
       :resourceType       "AllergyIntolerance",
       :code               {:id 102002},
       :patient
       {:resourceType "Patient",
        :id           "a087966b-41c6-450b-a386-106bfaa1bb72"}}
      {:verificationStatus "confirmed",
       :resourceType       "AllergyIntolerance",
       :code               {:id 8429000},
       :patient
       {:resourceType "Patient",
        :id           "a087966b-41c6-450b-a386-106bfaa1bb72"}}]})

  (def name-rules
    [{:form [:demographic-patientInfo-name {:ih/sight  :ihs/str<->vector
                                            :separator "e"} [0]]
      :fhir [:name [0] :given [0]]}

     {:form [:demographic-patientInfo-name {:ih/sight  :ihs/str<->vector
                                            :separator "e"} [1]]
      :fhir [:name [0] :family]}])

  (testing "Split/join by e"
    (matcho/assert
     {:name [{:given ["Ivan P"], :family "trov"}]}
     (transform
      sample-data
      {}
      name-rules
      [:form :fhir])))

  (testing "Name returned to original form"
    (matcho/assert
     {:demographic-patientInfo-name "Ivan Petrov"}
     (->
      (transform
       sample-data
       {}
       name-rules
       [:form :fhir])
      (transform
       {}
       name-rules
       [:fhir :form]))))

  (matcho/assert
   {:allergies
    [{:verificationStatus "confirmed",
      :resourceType       "AllergyIntolerance",
      :patient
      {:resourceType "Patient", :id "a087966b-41c6-450b-a386-106bfaa1bb72"},
      :code               102002}
     {:verificationStatus "confirmed",
      :resourceType       "AllergyIntolerance",
      :patient
      {:resourceType "Patient", :id "a087966b-41c6-450b-a386-106bfaa1bb72"},
      :code               8429000}],
    :birthDate "2010-10-10",
    :name      [{:given ["Ivan P"], :family "trov"}],
    :telecom
    [{:system "phone", :value "+12222222222"}
     {:system "email", :value "ivanpetrov@email.com"}],
    :gender    "Male"}
   (transform sample-data {} form-fhir-rules [:form :fhir])))


(deftest fill-from-value
  (matcho/assert
   {:allergies [{:category "food"
                 :resourceType "AllergyIntolerance"
                 :patient {:id "uuid-blblablabla"
                           :resourceType "Patient"}}]}
   (->
    #:ih {:direction [:form :fhir]
          :data      {:form {:allergies [{:reaction "anemia"
                                          :category "food"}
                                         {:category "good"}]}
                      :fhir {}}
          :values    {:allergy-rt "AllergyIntolerance"
                      :patient-rt "Patient"
                      :patient-id "uuid-blblablabla"
                      :vector     []}

          :rules [;; {:ih/value {:fhir [:vector]}
                  ;;  :fhir     [:allergies]}
                  {:form [:allergies [:*] :category]
                   :fhir [:allergies [:*] :category]}
                  {:ih/value {:fhir [:allergy-rt]}
                   :fhir     [:allergies [:*] :resourceType]}
                  {:ih/value {:fhir [:patient-id]}
                   :fhir     [:allergies [:*] :patient :id]}
                  {:ih/value {:fhir [:patient-rt]}
                   :fhir     [:allergies [:*] :patient :resourceType]}]}
    (execute)
    :ih/data
    :fhir
    )))

:patient-shell
:patient-mrn-shell
:name-shell
:practitioner-shell

{:patients []
 :practitoners []}
[:patient-shell :practitioner-shell]

(->
 #:ih {:direction [:left :right]

       :values {:default-name "Name not provided"
                :types [1]}
       :rules  [{:left [:allergies [:*]]
                 :right [:my-allergies [:*]]
                 ;; :ih/nested-shell [:name :id] :form<->fhir-allergy
                 }

                ;; [:form :intermediate :fhir]
                ;; [:form :intermediate]
                ;; [:intermediate :fhir]

                {:ih/value {:right [:default-name]}
                 :left [:ih/values :types [:*]]
                 :right    [:my-allergies [:*] :type]}
                ]

       :data {:left {:allergies [{:reaction "animea"} {:reaction "other"}]}
              :right {:allergies [{} {} {:type "super"}]}
              }}
 execute
 :ih/data
 :right)

(deftest copy-nils-test
  (testing "Basic nil copying"
    (matcho/assert
     {:f {:in nil}, :t {:out nil}}
     (->
      #:ih{:direction [:f :t]
           :data {:f {:in nil}}
           :rules [{:f [:in]
                    :t [:out]}]}
      (execute)
      :ih/data)))
  (testing "Deep nil copying"
    (matcho/assert
     {:f {:in {:layer [nil]}}, :t {:out [nil]}}
     (->
      #:ih{:direction [:f :t]
           :data {:f {:in {:layer [nil]}}}
           :rules [{:f [:in :layer]
                    :t [:out]}]}
      (execute)
      :ih/data)))
  (testing "Nil copying with wildcards"
    (matcho/assert
     {:form {:phones [{:txt nil}
                      {:txt "+2 222"}]}
      :fhir {:telecom [{:system "phone"
                          :value  nil}
                         {:system "phone"
                          :value  "+2 222"}]}}
    (->
     #:ih{:direction [:form :fhir]

          :rules [{:form [:phones [:*] :txt]
                   :fhir [:telecom [:*] :value]}]
          :data  {:form {:phones [{:txt nil}
                                  {:txt "+2 222"}]}
                  :fhir {:telecom [{:system "phone"
                                    :value  "+3 333"}
                                   {:system "phone"
                                    :value  "+4 444"}]}}}
     (execute)
     :ih/data))))
