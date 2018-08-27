<img align="right" src="https://user-images.githubusercontent.com/1218615/43453962-374b7d44-94c4-11e8-83c2-5d9c703fce36.png" width=300/>

# ironhide [![CircleCI](https://circleci.com/gh/HealthSamurai/ironhide.svg?style=shield)](https://circleci.com/gh/HealthSamurai/ironhide) [![Join gitter](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/healthsamurai/ironhide)

Ironhide, the data transformer.

[-> free online demo <-](https://healthsamurai.github.io/ironhide/)

[![Clojars Project](http://clojars.org/healthsamurai/ironhide/latest-version.svg)](http://clojars.org/healthsamurai/ironhide)

## Idea

Create a language agnostic bidirectional data-driven transformation [dsl](https://en.wikipedia.org/wiki/Domain-specific_language) for fun and profit.

### Problem

There are a lot of data, which has to be represented in different shapes. For
this reason created a lot of query/transformation languages such as
[XSLT](https://www.w3.org/standards/xml/transformation),
[AWK](https://en.wikipedia.org/wiki/AWK), etc, but most of them have a
significant disadvantage: they work only in one direction and you can't get
original data from the result of transformation.

It worth noting that there are other languages like
[boomerang](http://www.seas.upenn.edu/~harmony/), which doesn't have this
significant (in some cases) weakness, but have others : )

Simplified real life example of representation person name in different systems:

```json
"form": {
  "name": "Firstname Lastname"
}
```

```json
"fhir": {
  "name": {
    "given": [
      "Firstname"
    ],
    "family": "Lastname"
  }
}
```

By different reasons both respresentations should be availiable and moreover
syncronized. Syncronization can be done by implementing and applying when needed
two function *f* and *f <sup>-1</sup>* for each field or subset of fields, but
you already probably know how hard to maintain such code?)

It is hard to implement such functions for big nested tree data structures and
much harder to keep *f <sup>-1</sup>* in sync with *f*.

### Solution

[ironhide](https://github.com/abcdw/ironhide) is attempt to create a
bidirectional data transformation language described by a data structure stored
in [EDN](https://github.com/edn-format/edn) (you can think about edn like a
better [JSON](https://json.org/)). `ironhide` still in early stage of
development, but already covers some practical usecases. It's also declarative,
bidirectional, data-driven and simple.

Following code in ironhide solves example above:

```clj
#:ih{:direction [:fhir :form]
     :rules     [{:form [:name :ihs/str<->vector [0]]
                  :fhir [:name [0] :given [0]]}

                 {:form [:name :ihs/str<->vector [1]]
                  :fhir [:name [0] :family]}]}
```

The direction of transformation controlled by `:ih/direction` key and this
simple snippet allows to transform data in both ways out of the box.

## Usage

This section contains examples of clojure ironhide interpreter usage with little
explanation, to get the taste of dsl capabilities. More detailed info provided
in [Description](#description) section.

Add to `:deps` in **deps.edn**:

```clj
healthsamurai/ironhide {:mvn/version "RELEASE"}
```

**hello_world.clj**:

```clj
(ns hello-world.core
  (:require [ironhide.core :as ih]))
  
;; (ih/execute shell)
;; (ih/get-data shell)
;; (ih/get-data shell :charge1)
```

### Field to field mapping

```clj
(def update-name-shell
  #:ih{:direction [:form :form-2]

       :rules [{:form   [:name]
                :form-2 [:fullname]}]
       :data  {:form   {:name "Full Name"}
               :form-2 {:fullname "Old Name"}}})

(get-data update-name-shell)
;; => {:form {:name "Full Name"}, :form-2 {:fullname "Full Name"}}
```


### Create missing fields

```clj
(def create-name-shell
  #:ih{:direction [:form :form-2]

       :rules [{:form   [:name]
                :form-2 [:fullname]}]
       :data  {:form   {:name "Full Name"}}})

(get-data create-name-shell)
;; => {:form {:name "Full Name"}, :form-2 {:fullname "Full Name"}}
```

### Default values

```clj
(def default-name-shell
  #:ih{:direction [:form :form-2]

       :values {:person/name "Name not provided by the form"}
       :rules  [{:form     [:name]
                 :form-2   [:fullname]
                 :ih/value {:form-2 [:ih/values :person/name]}}]
       :data   {:form   {}
                :form-2 {:fullname "Old Name"}}})

(get-data default-name-shell)
;; => {:form {}, :form-2 {:fullname "Name not provided by the form"}}
```

### Sight for string

```clj
(def sight-name-shell
  #:ih{:direction [:form :fhir]

       :rules [{:form   [:name :ihs/str<->vector [0]]
                :fhir [:name [0] :given [0]]}]
       :data  {:form   {:name "Full Name"}}})

(get-data sight-name-shell)
;; => {:form {:name "Full Name"}, :fhir {:name [{:given ["Full"]}]}}
```

See [Sight](#sight) section for the detailed explanation.

### Update and create if not-exists

```clj
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
```

<!-- ### Multi-dimensional update -->

### Micro

```clj
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
```

Add parametrized micro.

## Description

`shell` is a tree datastructure, which contains declaration of transformation
rules + data itself. `ironhide` interpreter can execute `shell`'s.

It consists of few main parts:

* `:ih/data` a data for transformation
* `:ih/values` similar to previous one, but used mostly for default values
* `:ih/micros` shortcuts for long repetitive pathes in rules
* `:ih/direction` default transformation direction (rule can define its own)
* `:ih/rules` vector of transformation rules

Simple `shell` executed with `get-data`:

```clj
(get-data
 #:ih{:direction [:form :fhir]
      :data      {:form {:first-name "Firstname"}
                  :fhir {}}
      :rules     [{:form [:first-name]
                   :fhir [:name [0] :given [0]]}]})
;; => {:form {:first-name "Firstname"}, :fhir {:name [{:given ["Firstname"]}]}}
```

### Bullet

A `bullet` is any leaf value (map or vector also can be treated as a leaf
value).

```clj
;; {:k1 {:k2 :v3}}
[:k1] ;; => [[{:k2 :v3}]]
;; {:k2 :v3} is a bullet
```

### Charge

A `charge` is logical name (keyword) of the part of subtree inside the `shell`
most often under the `:ih/data` key.

Source `charge` in `:ih/direction` is used for getting `bullet`s and sink
`charge` for updating/creating.

```clj

#:ih{:direction [:form :fhir]
     :data      {:form {:first-name "Firstname"}
                 :fhir {}}}

;; :form and :fhir are charges
```

### Path and pelem

`Path` is a vector consist of `pelem`s, which describes how to get to
`bullet`s. Something similar to [XPath](https://en.wikipedia.org/wiki/XPath),
[JsonPath](http://goessner.net/articles/JsonPath/), but not exactly.

There are few types of `pelem`s:

* `mkey`
* `vnav`
* `sight`
* `micro`

| term      | definition                                                                                    |
|-----------|-----------------------------------------------------------------------------------------------|
| `mkey`    | a simple edn `:keyword`, which tells `shell` executor to navigate to specific key in the map. |
| `vnav`    | a vector, which consists of `vkey` and optional `vfilter`.                                    |
| `vkey`    | an `index` (some non-negative integer) or `wildcard` `:*` (keyword).                           |
| `vfilter` | a map used for pattern matching and templating                                                |
| `sight`   | `:ihs/` namespaced keyword or `{:ih/sight :ihs/sight-name :arg1 :value1}`         |
| `micro`   | `:ihm/` namespaced keyword or `{:ih/micro :ihm/micro-name :arg1 :value1}`                     |

Example of paths and `get-values` results: 

```clj

;; {:k1 {:k2 :v3}}
[:k1] ;; => [[{:k2 :v3}]]
[:k1 :k2] ;; => [[:v3]]

;; [{:a :b} {:k :v :k1 :v2}]
[[0]] ;; => [[{:a :b}]]
[[:*]] ;; => [[0 {:a :b}] [1 {:k :v :k1 :v1}]]
[[1 {:a :b}]] ;; => [[nil]]
[[:* {:k :v}]] ;; => [[0 {:k :v :k1 :v1}]]

;; {:name "Firstname, Secondname"}
[:name :ihs/str<->vector [0]] ;; => [["Firstname,"]]
;; [:name {:ih/sight :ihs/str<->vector :separator ", "} [0]]
[:ihm/first-name] ;; => [["Firstname"]]

;; [[1 2] [3 4 5]]
```

`get-values` always returns a magazine (vector of addressed) `bullet`s. Each
`wildcard` inside the path creates one dimension of address. Source and sink
path should have same number of `wildcard`s to make transformation possible.
`ironhide` interpreter will align the shape automatically (without deleting
existing data).

```clj
(get-values
 [[:v1 :v2] [:v3 :v4 :v5]]
 [[:*] [:*]])
;; => [[0 0 :v1] [0 1 :v2] [1 0 :v3] [1 1 :v4] [1 2 :v5]]
;; the result of get-values is a magazine
;; 1 2 - is a multi-dimensional address
;; :v5 is a bullet
```

### Sight

`sight` is a special `pelem`, which allows to percieve `bullet` differently.
It's useful when you want to treat a string as a vector of words for example:

```clj
;; {:name "Firstname Secondname"}
[:name :ihs/str<->vector [0]] ;; => [["Firstname"]]
```

It allows to navigate inside `bullet` differently and more preciesly, but don't
change original structure of it.


### Micro

`micro` is a parametrized shortcut for part of the path. 

```clj
(microexpand-path
 #:ih{:micros #:ihm {:name [:name [:index] :given [0]]}}
 [:ihm/name])
;; => [:name [:index] :given [0]]

(microexpand-path
 #:ih{:micros #:ihm {:name [:name [:index] :given [0]]}}
 [{:ih/micro :ihm/name :index 10}])
;; => [:name [10] :given [0]]
```

Default values for micros not supported yet.


### Rule

Rule specifies relation between `bullet`s. It is a map, which can contain few
different key types:

* `charge`, which associated with path to `bullet`
* `:ih/direction`, which associated with a pair of source and sink `charge`s
* `:ih/defaults`, which associated with map of `charge` keys and
  path-to-default-value values

```clj
{:form [:firstname]
 :fhir [:name [0] :given [0]]

 :ih/defaults  {:fhir [:ih/values :firstname]}
 :ih/direction [:form :fhir]}
```

## Thanks

Special thanks to:

* [Nathan Marz](https://github.com/nathanmarz) for [specter](https://github.com/nathanmarz/specter)
* [Nikolai Ryzhikov](https://github.com/niquola/) for [matcho](https://github.com/healthsamurai/matcho) and [2way](https://github.com/niquola/2way)

## Contribution

PRs are welcome, but merging are not guaranteed. Create issue or contact
[abcdw](https://github.com/abcdw) if you need or want.

## License

Copyright © 2018 HealthSamurai

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
