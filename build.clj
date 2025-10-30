(ns build
  (:require [clojure.tools.build.api :as b]
            [clojure.edn :as edn]))

(def lib 'tech.unravel/dscloj)
(def version "0.1.0-alpha")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; Read pom-data from deps.edn
(def deps-edn (edn/read-string (slurp "deps.edn")))
(def pom-data (:pom-data deps-edn))

(defn clean [_]
  (b/delete {:path "target"}))

(defn pom [_]
  (let [pom-opts {:lib lib
                  :version version
                  :basis basis
                  :src-dirs ["src"]
                  :target "."}
        pom-opts-with-data (cond-> pom-opts
                             (:description pom-data) (assoc :description (:description pom-data))
                             (:url pom-data) (assoc :url (:url pom-data))
                             (:licenses pom-data) (assoc :licenses (:licenses pom-data))
                             (:scm pom-data) (assoc :scm (:scm pom-data)))]
    (b/write-pom pom-opts-with-data)))

(defn jar [_]
  (pom nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))
