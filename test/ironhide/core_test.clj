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


;; (get-value {:name [{:given ["test" :value]}]} [:name [0] :given [0]])
;; => [["test"]]

;; (set-values
;;  [{:d :t1
;;    :v [{:k 0} {:k 1}]}

;;   {:d :t2
;;    :v [{} {}]}

;;   {:d :t1
;;    :v [{:k 2} {:k 3}]}]
;;  [[:* {:d :t1}] :v [:*] :k]
;;  (get-values [[:v1] [:v2 :v3]] [[:*] [:*]]))

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

(deftest set-value-test
  ;; (matcho/assert
  ;;  {:a [identity identity identity {:c 1 :v :test}]}
  ;;  (tf/set-value
  ;;   {:a [:first-value
  ;;        {:c 1 :v 1}
  ;;        :second
  ;;        {:c 1 :v 11}]}
  ;;   {}
  ;;   [:a {:c 1} [1] :v] :test))

  ;; (matcho/assert
  ;;  {:a [{:c 1 :v [{:d 2 :s :test}]}]}
  ;;  {}
  ;;  (tf/set-value {} [:a {:c 1} [] :v {:d 2} [] :s] :test))
  )


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


(transform
 [{:address ["123 HAPPY AVE"
             "NOWHERE TOWN"
             "ALBUKERKA"
             "CA"
             "98000"
             "USA"]
   :name "blabl"}]
 nil
 [{:hl7 [[:*] :address [0]]
   :fhir [:address [:*] :line [0]]}
  ;; {:hl7  [:address [1]]
  ;;  :fhir [:address :line [1]]}
  ;; {:hl7  [:address [2]]
  ;;  :fhir [:address :line [2]]}

  ;; {:hl7  [:address [2]]
  ;;  :fhir [:address :city]}
  ;; {:hl7  [:address [3]]
  ;;  :fhir [:address :state]}
  ;; {:hl7  [:address [4]]
  ;;  :fhir [:address :postalCode]}
  ;; {:hl7  [:address [5]]
  ;;  :fhir [:address :country]}
  ]

 [:hl7 :fhir]
 )

(sp/setval [(sp/filterer (constantly true)) 1] :test [:a])

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
    [
     {:form [:allergies-knownAllergies [:*]]

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

  ;; #spy/p
  ;; (transform sample-data {} form-fhir-rules [:form :fhir])

  ;; (tf/transform {:name [{:family "Petrov" :given ["Ivan"]}]}
  ;;               {}
  ;;               form-fhir-rules
  ;;               [:fhir :form])

  )


(defn gen-uuid! []
  (str (java.util.UUID/randomUUID)))

;; (defmethod u/*fn ::create-ctx
;;   [ctx]
;;   {:form-data (->
;;                (io/resource "definitions/sample-form.edn")
;;                slurp
;;                edn/read-string
;;                :data)
;;    :bundle    []})

;; (defmethod u/*fn ::init-ids
;;  [ctx]
;;   {:ids {:patient/id (gen-uuid!)}})

;; (defmethod u/*fn ::fhir-patient
;;   [{bundle                   :bundle
;;     form-data                :form-data
;;     {id :patient/id :as ids} :ids :as ctx}]
;;   (let [{pname  :demographic-patientInfo-name
;;          gender :demographic-patientInfo-gender
;;          dob    :demographic-patientInfo-dob
;;          mrn    :demographic-patientInfo-mrn
;;          phone  :demographics-patientInfo-cellPhoneNumber
;;          email  :demographics-patientInfo-email} form-data

;;         [first-name family] (cstr/split pname #" ")]
;;     {:bundle
;;      (->>
;;       {:id           id
;;        :resourceType "Patient"
;;        :name         [{:given  [first-name]
;;                        :family family}]
;;        :gender       (cstr/lower-case gender)
;;        :birthDate    dob
;;        :telecom      [{:system "phone"
;;                        :value  phone}
;;                       {:system "email"
;;                        :value  email}]}
;;       (conj bundle))}))


;; (defmethod u/*fn ::fhir-allergies
;;   [{bundle                   :bundle
;;     form-data                :form-data
;;     {patient-id :patient/id} :ids
;;     :as                      ctx}]
;;   {:bundle

;;    (->>
;;     (map (fn [x]
;;            {:verificationStatus "confirmed"
;;             :resourceType       "AllergyIntolerance"
;;             :code               {:id x}
;;             :patient            {:resourceType "Patient"
;;                                  :id           patient-id}})
;;          (:allergies-knownAllergies form-data))
;;     (concat bundle))})

;; (defn publish-patient [patient]
;;   (http/put
;;    (str "http://localhost:8080/Patient/" (:id patient))
;;    {:headers {:content-type "application/json"}
;;     :body (json/generate-string patient)}))

(def fhir<->uhn
  {:rules
   [;; {0 [:patient :name [0] :given [0]]
    ;;  1 [:demographic-patientInfo-firstname]

    ;;  :convert {0 identity ;; cstr/upper-case
    ;;            1 identity}}

    ;; {0 [:patient :id]

    ;;  :default {0 :patient/id}}

    ;; {0 [:patient :resourceType]

    ;;  :default {0 "Patient"}}

    ;; {0 [:patient :name 0 :family]
    ;;  1 [:demographic-patientInfo-lastname]}

    ;; {0 [:patient :gender]
    ;;  1 [:demographic-patientInfo-gender]

    ;;  :convert {0 {"Male"   "male"
    ;;               "Female" "female"}}}

    ;; {0 [:patient :telecom {:system "phone"} [0] :value]
    ;;  1 [:demographics-patientInfo-cellPhoneNumber]}

    {0 [:allergies {:verificationStatus "confirmed"
                    :resourceType       "AllergyIntolerance"
                    :patient            {:resourceType "Patient"
                                         :id           :patient/id}}
        []
        :code :id]
     1 [:allergies-knownAllergies []]}

    ]})

;; (def form-data
;;   (->
;;    (u/*apply [::create-ctx] {})
;;    :form-data))



;; (clojure.pprint/pprint
;;  (transform form-data {} (:rules fhir<->uhn) [1 0]))


;; (tf/get-value [[1 2] [3]] [[:*] [:*]])
;; => [[0 0 1] [0 1 2] [1 0 3]]
;; (tf/get-value [[[1 2] [3 4 5]] [[6 7 8] [9 0]]]
;;               [[:*] [:*] [:*]])
;; => [
;; [0 0 0 1] [0 0 1 2] [0 1 0 3] [0 1 1 4] [0 1 2 5]
;; [1 0 0 6] [1 0 1 7] [1 0 2 8] [1 1 0 9] [1 1 1 0]]
;; [[2]
;;  [2 2]
;;  [2 3 3 2]]

(def src
  [[[:v1 :v2 :v3] [:v3 :v4 :v5]] [[:v6 :v7 :v8] [:v9 :v0 :v10]]])

(def sink
  [[[1 2] [3 4 5]] [[6 7 8] [9 0]]])


;; (count-branching sink [[:*] :a :b [:*] :c])


;; (println :=======)
;; (println
;;  (fillup-acc src sink [[:*] [:*] [:*]] [[:*] [:*] [:*]]))
;; => [((0) (0 0) (1 0 0 1)) ([] [] nil) [[] [[:*]] [[:*] [:*]]]]

;; (sp/setval
;;  [
;;   ALL-INDEXED
;;   sp/END
;;   ]
;;  [:test]
;;  sink
;;  )

;; (sp/select
;;  [sp/ALL
;;   sp/ALL
;;   ALL-INDEXED]
;;  sink)

;; (sp/setval
;;  ;; [:b (sp/filterer (constantly true)) sp/NIL->VECTOR sp/ALL :c]
;;  (:insert-path ip)
;;  ;; [:v1 :v2 :v3]
;;  ;; (fn [i x] (get [:v1 :v2 :v3] i))
;;  ds2)

;; (sp/setval [:a (sp/filterer (constantly true)) :c] [:v3 :v4] {:a [{:c :v1} {:c :v2}]})
;; (sp/select [sp/INDEXED-VALS] [:v3 :v4])

;; (sp/select
;;  [:a sp/INDEXED-VALS (sp/collect-one 0) 1]
;;  ;; (fn [i x] (get [7 5 3] i))
;;  {:a [{:c :v1} {:c :v2}]})

;; (set-value {} [:a {:c 1} [] :v {:d 2} [] :s] :test)
;; (println :===========)
;; (set-value {:a [1 2]} [:a []] :test) 
;; (sp/select [:a sp/ALL] {:a [1 2]})
;; (set-value {} [:patient :id] :patient/id)

;; (defn- fill-with-templates [acc path values]
;;   (let [{:keys [insert-path sub-paths]
;;          :as   paths} (get-insert-paths path)
;;         cardinalities (get-max-cardinality values)]
;;     ;; (loop [acc acc [[sp value] & rps :as sps] sub-paths i 0]
;;     ;;   ;; (clojure.pprint/pprint value)
;;     ;;   (if (not-empty sps)
;;     ;;     (let [insert-subpath (conj (into [] (drop-last sp)) sp/AFTER-ELEM)
;;     ;;           selected-count (count (sp/select sp acc))
;;     ;;           ;; _ (println sp)
;;     ;;           ;; _              (println ">>>>" (sp/select sp acc) selected-count i)
;;     ;;           ;; new-acc        (if (not= count 0)
;;     ;;           ;;                  (sp/setval insert-subpath value acc)
;;     ;;           ;;                  acc)
;;     ;;           new-acc        acc
;;     ;;           ]
;;     ;;       (recur new-acc rps (inc i)))
;;     ;;     acc))
;;     ))

;; (defn get-real-insert-path [path]
;;   (reduce
;;    (fn [acc x]
;;      (let [next-step (if map?)])
;;      (conj acc next-step)
;;      )
;;    []
;;    path)
;;   )

;; [:a {} [] :c]
;; [:a filterer sp/INDEXD]
;; [path v] -> {path v}

;; (sp/setval
;;  [0 (sp/nil->val {}) :a]
;;  :test
;;  [])


(comment
  (println "=======================")
  (clojure.pprint/pprint form-data)
  (println "-----------------------")

  (def fhir-bundle
    (->
     form-data
     (transform (:rules fhir<->uhn) [1 0])
     clojure.pprint/pprint))

  (println "-----------------------")
  (clojure.pprint/pprint
   (->
    (u/*apply [::create-ctx ::init-ids ::fhir-patient ::fhir-allergies] {})
    :bundle
    ;; first
    ;; publish-patient
    ))
  (println "=======================")


  (get-values {:a {:b [{:c 2 :d 3} {:c 2 :d 4}]}} [:a :b {:c 2} ])

  (sp/select [:a :b (sp/filterer #(m/valid? {:c 2} %)) sp/ALL]
             {:a {:b [{:c 2 :d 3} {:c 2 :d 4}]}})

  (set-values {} [:a {:c 1} [] :v {:d 2} [] :s] :test))

;; (def transformer
;;   #:ih{:micros #:ihm {:telecom    [:telecom [:%1] :value]
;;                       :first-name [:name [0] :given [0]]}

;;        :data {:form {:name "Test Name"}
;;               :fhir {}}

;;        :values {:patient-id "Patient/UUID"} ;; should be attacheable

;;        :direction [:form :fhir]

;;        :rules
;;        [{:ih/direction [:sub-form :fhir]
;;          :sub-form     [:name :ihp/str<->vector [0]]
;;          :ih/defaults  {:fhir [:ih/values :patient-id]}
;;          :fhir         [:ihm/first-name]}]})


;; #spy/p
;; (get-data
;;  #:ih{:direction [:form :fhir]
;;       :data      {:form {:first-name "Firstname"}
;;                   :fhir {}}
;;       :rules     [{:form [:first-name]
;;                    :fhir [:name [0] :given [0]]}]})
;; => {:form {:first-name "Firstname"}, :fhir {:name [{:given ["Firstname"]}]}}

;; #spy/p
;; (json/parse-string
;;  (json/generate-string transformer)
;;  true)

;; #spy/p
;; (transform-once transformation (first (:ih/rules transformation))),

;; (println :========)
;; #spy/p
;; (get-data transformer :fhir)

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
    :fhir)))

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
      :ih/data))))
