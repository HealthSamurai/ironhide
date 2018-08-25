(ns ironhide.core
  (:require [com.rpl.specter :as sp]
            [clojure.string :as cstr]))

(defn- match-recur [errors path x pattern]
  (cond
    (and (map? x)
         (map? pattern))
    (reduce (fn [errors [k v]]
              (let [path  (conj path k)
                    ev (get x k)]
                (match-recur errors path ev v)))
            errors pattern)

    (and (vector? pattern)
         (seqable? x))
    (reduce (fn [errors [k v]]
              (let [path (conj path k)
                    ev  (nth (vec x) k nil)]
                (match-recur errors path ev v)))
            errors
            (map (fn [x i] [i x]) pattern (range)))

    :else (let [err (when-not (= pattern x) {:expected pattern :but x})]
            (if err
              (conj errors (assoc err :path path))
              errors))))

(defn- match*
  [x & patterns]
  (reduce (fn [acc pattern] (match-recur acc [] x pattern)) [] patterns))

(defn- m-valid? [pattern x]
  (if (empty? (match* x pattern))
    true
    false))


(defmulti get-sight
  "Returns pair of parse/unparse functions"
  (fn [key args] key))

(defmethod get-sight :ihs/str<->vector [key args]
  (let [separator (get args :separator " ")]
    [#(if % (cstr/split % (re-pattern separator)) [])
     #(cstr/join separator %)]))

(defmethod get-sight :default [key args]
  [identity identity])

(defn- wildcard? [pelem]
  (m-valid? [:*] pelem))

(defn- has-wildcards? [path]
  (not-empty (filter wildcard? path)))

(def ^:private ALL-INDEXED [sp/INDEXED-VALS (sp/collect-one 0) 1])

(def ^:private TREE-VALUES
	(sp/recursive-path [] p
	  (sp/if-path vector?
                      [sp/ALL p]
                      (sp/if-path map?
                                  [sp/MAP-VALS p]
                                  sp/STAY))))

(defn- vfilter->template [vfilter next-pelem]
  (if vfilter
    vfilter
    (if (vector? next-pelem) [] nil)))

(defn- get-vfilter-fn [vfilter]
  (if vfilter
    (fn [x] (m-valid? vfilter x))
    (constantly true)))

(defn- vec->specter [[navigator & [vfilter] :as pelem] next-pelem]
  (let [filter-fn (get-vfilter-fn vfilter)
        template  (vfilter->template vfilter next-pelem)]
    [sp/NIL->VECTOR

     (if (wildcard? pelem)
       [(sp/filterer filter-fn) ALL-INDEXED]

       (if vfilter
         (sp/if-path #(empty? (filter filter-fn %))
                     [sp/END 0 (sp/nil->val template)]
                     [(sp/filterer filter-fn) navigator])
         [(sp/nil->val template) navigator]))]))

;; (sp/setval [(vec->specter [0] []) 0] :test [])
;; => [[:test]]

(defn- ih? [x]
  (and (keyword? x)
       (= "ih" (namespace x))))

(defn- special? [ns key x]
  (or
   (and (keyword? x) (= ns (namespace x)))
   (and (map? x) (contains? x key))))

(defn- micro? [x]
  (special? "ihm" :ih/micro x))

(defn- sight? [x]
  (special? "ihs" :ih/sight x))

(defn- get-special-name [type pelem]
  (cond
    (keyword? pelem) pelem
    (map? pelem)     (type pelem)))

(defn- get-micro-name [pelem]
  (get-special-name :ih/micro pelem))

(defn- get-sight-name [pelem]
  (get-special-name :ih/sight pelem))

(defn- get-args-from-pelem [pelem]
  (if (map? pelem) (into {} (remove #(ih? (first %)) pelem)) {}))

(defn- get-sight-from-pelem [pelem]
  (let [sight-name      (get-sight-name pelem)
        args            (get-args-from-pelem pelem)
        [parse unparse] (get-sight sight-name args)]
    (sp/parser parse unparse)))

(defn- sight->specter [pelem]
  [(get-sight-from-pelem pelem)])

(defn- pelems->specter [pelem next-pelem]
  (cond
    (vector? pelem)  (vec->specter pelem next-pelem)
    (sight? pelem)   (sight->specter pelem)
    (keyword? pelem) [pelem]))

(defn path->sp-path [path]
  (reduce
   (fn [acc [pelem next-pelem]]
     (concat acc (pelems->specter pelem next-pelem)))
   []
   (partition 2 1 [nil] path)))

(defn get-values [data path]
  (let [sp-path (path->sp-path path)]
    (if (has-wildcards? path)
      (sp/select sp-path data)
      [(sp/select sp-path data)])))

(defn- path->value [values]
  (->>
   (for [value values]
     [(butlast value) (last value)])
   (into {})))

(defn set-values [data path values]
  (let [values  (if (vector? values)
                  (path->value values)
                  values)
        sp-path (path->sp-path path)]
    (sp/transform
     sp-path
     (fn [& v]
       (let [path  (butlast v)
             value (last v)]
         (or (values path) value)))
     data)))

(defn- get-templates [path]
  (for [[[_ vfilter] next-pelem]
        (filter (comp wildcard? first) (partition 2 1 [nil] path))]
    (vfilter->template vfilter next-pelem)))

;; (get-templates [[:* {:a :b}] :a [:*] [:*]])
;; => ({:a :b} [] nil)

(defn- splitcat-by-wildcard [path]
  (get
   (reduce
    (fn [{:keys [curr-path] :as acc} pelem]
      (let [new-path (conj curr-path pelem)]
        (cond-> (assoc acc :curr-path new-path)
          (wildcard? pelem) (update :paths conj new-path))))
    {:curr-path []
     :paths     []}
    path)
   :paths))

;; (splitcat-by-wildcard [[:*] :a :b [:*] :v])
;; => [[[:*]] [[:*] :a :b [:*]]]

(defn- counting-sp-path [path]
  (let [[_ vfilter] (last path)]
    (concat
     (path->sp-path (butlast path))
     (if vfilter [(sp/filterer (get-vfilter-fn vfilter))] []))))

(defn- count-matched-nodes [data path]
  (let [res (sp/select (counting-sp-path path) data)]
    (if (has-wildcards? (butlast path))
      (mapv #(count (last %)) res)
      [(count (first res))])))

(defn- tree-shape [data path]
  (map #(count-matched-nodes data %)
       (splitcat-by-wildcard path)))

(defn- count-shape-diff [source-shape sink-shape]
  (map #(map - %1 (concat %2 (repeat 0)))
       source-shape
       sink-shape))

(defn- add-level-elems [acc path elem ncounts]
  (let [wc?        (wildcard? (last path))
        first-part (butlast path)
        vfilter-fn    (get-vfilter-fn (if wc? (second (last path))))

        insert-path
        (path->sp-path
         (if wc? first-part path))

        select-path
        (if wc?
          (concat
           (path->sp-path (butlast path))
           [(sp/filterer vfilter-fn)])
          path)

        insert-fn
        (into {} (mapv (fn [[& args] ncount]
                         [(butlast args) (repeat ncount elem)])
                       (sp/select select-path acc)
                       ncounts))]

    (sp/transform
     [insert-path sp/END]
     (fn [& args]
       (insert-fn (butlast args)))
     acc)))

(defn- add-templates [source-data sink-data [source-path sink-path]]
  (let [diff (count-shape-diff
              (tree-shape source-data source-path)
              (tree-shape sink-data sink-path))

        defaults        (get-templates sink-path)
        branching-paths (splitcat-by-wildcard sink-path)]

    (loop [res                  sink-data
           [path & rpaths]      branching-paths
           [elem & relems]      defaults
           [ncounts & rncounts] diff]
      (if ncounts
        (recur
         (add-level-elems res path elem ncounts)
         rpaths
         relems
         rncounts)
        res))))

(defn- get-full-path [path & [prefix]]
  (if (ih? (first path))
    path
    (into prefix path)))

(defn- selectable?
  "Returns true when ironhide path exists in context (ctx)
   and false when ironhide path not exists in context (ctx).
   Examples:
     (selectable? {:b {nil 1}} [:b nil :cc]) => false
     (selectable? {:b {nil 2}} [:b nil])     => true
     (selectable? {:b {nil 2}} [:b :c])      => false
     (selectable? {:a nil} [:a])             => true
     (selectable? {} [:a])                   => false
     (selectable? {:b {nil nil}} [:b nil])   => true
     (selectable? {:a [[:v1] [:v2 :v3 :v4]]} [:a [:*] [:*]])    => true
     (selectable? {:a [[:v1] [:v2 :v3 :v4]]} [:a [:*] [:*] :w]) => false
   "
  [ctx path]
  (nil? (some #(nil? %)
            (sp/select TREE-VALUES
                       (sp/select
                        (path->sp-path path)
                        (sp/transform TREE-VALUES #(if (nil? %) :placeholder %) ctx))))))

(defn- apply-rule [ctx rule]
  (let [[from to] (or (:ih/direction rule)
                      (:ih/direction ctx)
                      [:data :data])

        {source-path from
         sink-path   to} rule

        full-source-path (get-full-path source-path [:ih/data from])
        full-sink-path   (get-full-path sink-path [:ih/data to])

        value-path (get-in rule [:ih/value to])

        full-value-path (get-full-path value-path [:ih/values])

        source-values (get-values ctx full-source-path)
        value         (get-values ctx full-value-path)

        empty-values? (fn [x] (m-valid? [[nil]] x))
        values        (if (or (not (selectable? ctx full-source-path)) (nil? source-path))
                        nil
                        source-values)]
    (if sink-path
      (cond
        values
        (set-values
         (add-templates ctx ctx [full-source-path full-sink-path])
         full-sink-path
         values)

        (and (selectable? ctx full-value-path) (not (nil? value-path)))
        (set-values
         ctx
         full-sink-path
         (fn [k] (first (first value))))

        :else
        ctx)
      ctx)))

(defn- microexpand [micro pelem]
  (let [values   (get-args-from-pelem pelem)
        keys-set (set (remove ih? (keys values)))]
    (sp/transform [(sp/walker #(keys-set %))] values micro)))

;; (microexpand [:telecom [:%1] :value] {:%1 :*})
;; => [:telecom [:*] :value]

(defn- pred-from-micro-name [micro-name]
  (fn [x]
    (or (and (keyword? x) (= micro-name x))
        (and (map? x) (m-valid? {:ih/micro micro-name} x)))))

(defn- microexpand-path [{micros :ih/micros} path]
  (reduce
   (fn [path pelem]
     (let [micro-name (get-micro-name pelem)
           micro      (get micros micro-name)]
       (if micro
         (into path (microexpand micro pelem))
         (conj path pelem))))
   []
   path))

;; (microexpand-path
;;  {:ih/micros #:ihm {:name [:name [0]]
;;                     :given [:given [:ind]]}}
;;  [:ihm/name {:ih/micro :ihm/given :ind 1}])
;; => [:name [0] :given [1]]

(defn microexpand-shell [{micros :ih/micros :as ctx}]
  (let []
    (sp/transform
     [:ih/rules sp/ALL (sp/filterer #(not (ih? (first %)))) sp/ALL 1]
     (fn [path]
       (microexpand-path ctx path))
     ctx)))

(defn execute [shell]
  (let [expanded-shell    (microexpand-shell shell)
        {rules :ih/rules} expanded-shell]
    (reduce
     (fn [ctx rule]
       (apply-rule ctx rule))
     expanded-shell
     rules)))

(defn get-data [shell & [key]]
  (->
   (execute shell)
   :ih/data
   (#(get % key %))))

;; (-> {:name "Firstname, Secondname"}
;;     (get-values [:name :ihp/str<->vector [0]]))
;; => [["Firstname,"]]
;; => [[0 {:a :b}] [1 {:k :v}]]

(microexpand-path
 #:ih{:micros #:ihm {:name [:name [:index] :given [0]]}}
 [:ihm/name])
;; => [:name [:index] :given [0]]
(microexpand-path
 #:ih{:micros #:ihm {:name [:name [:index] :given [0]]}}
 [{:ih/micro :ihm/name :index 10}])
;; => [:name [10] :given [0]]

(get-values
 [[:v1 :v2] [:v3 :v4 :v5]]
 [[:*] [:*]])
;; => [[0 0 :v1] [0 1 :v2] [1 0 :v3] [1 1 :v4] [1 2 :v5]]
;; the result of get-values is a magazine
;; 1 2 - is a multi-dimensional address
;; :v5 is a bullet

