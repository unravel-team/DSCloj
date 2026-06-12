(ns build
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'tech.unravel/dscloj)
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))

;; The version is maj.min.x: maj.min comes from the VERSION file (bump it
;; with `make release-major` / `make release-minor`), x is the number of git
;; commits at build time.
(def version
  (format "%s.%s" (str/trim (slurp "VERSION")) (b/git-count-revs nil)))

(def jar-file (format "target/%s-%s.jar" (name lib) version))

;; Read pom-data from deps.edn.
(def deps-edn (edn/read-string (slurp "deps.edn")))
(def pom-data (:pom-data deps-edn))

(defn- licenses-pom-data
  [licenses]
  (when (seq licenses)
    (into [:licenses]
          (for [{:keys [name url]} licenses]
            (cond-> [:license]
              name (conj [:name name])
              url (conj [:url url]))))))

(defn- extra-pom-data
  []
  (cond-> []
    (:description pom-data) (conj [:description (:description pom-data)])
    (:url pom-data) (conj [:url (:url pom-data)])
    (seq (:licenses pom-data)) (conj (licenses-pom-data (:licenses pom-data)))))

(defn- pom-opts
  []
  {:lib lib
   :version version
   :basis basis
   ;; Root pom.xml is generated/ignored here; do not reuse stale generated POMs.
   :src-pom "pom-template.xml"
   :src-dirs ["src"]
   :resource-dirs ["resources"]
   :class-dir class-dir
   :scm (:scm pom-data)
   :pom-data (extra-pom-data)})

(defn clean [_]
  (b/delete {:path "target"}))

(defn pom [_]
  (b/write-pom (pom-opts)))

(defn jar [_]
  (b/delete {:path class-dir})
  (pom nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  (println "Built" jar-file))

(defn install [_]
  (jar nil)
  (b/install {:basis basis
              :lib lib
              :version version
              :jar-file jar-file
              :class-dir class-dir}))

(defn deploy [_]
  (jar nil)
  (println "Deploying" version "to Clojars...")
  (dd/deploy {:installer :remote
              :artifact (b/resolve-path jar-file)
              :pom-file (b/pom-path {:lib lib
                                     :class-dir class-dir})
              :sign-releases? false}))
