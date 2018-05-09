(ns figwheel.main
  #?(:clj
       (:require
        [cljs.analyzer :as ana]
        [cljs.analyzer.api :as ana-api]
        [cljs.build.api :as bapi]
        [cljs.cli :as cli]
        [cljs.env]
        [cljs.main :as cm]
        [cljs.repl]
        [cljs.repl.figwheel]
        [cljs.util]
        [clojure.java.io :as io]
        [clojure.pprint :refer [pprint]]
        [clojure.string :as string]
        [clojure.edn :as edn]
        [clojure.tools.reader.edn :as redn]
        [clojure.tools.reader.reader-types :as rtypes]
        [figwheel.core :as fw-core]
        [figwheel.main.ansi-party :as ansip]
        [figwheel.main.logging :as log]
        [figwheel.main.util :as fw-util]
        [figwheel.main.watching :as fww]
        [figwheel.repl :as fw-repl]
        [figwheel.tools.exceptions :as fig-ex]))
  #?(:clj
     (:import
      [java.io StringReader]
      [java.net.InetAddress]
      [java.net.URI]
      [java.net.URLEncoder]
      [java.nio.file.Paths]))
  #?(:cljs
     (:require-macros [figwheel.main])))

#?(:clj
   (do

(def ^:dynamic *base-config*)
(def ^:dynamic *config*)

;; TODO put this in figwheel config
#_(.setLevel log/*logger* java.util.logging.Level/ALL)

(defonce process-unique (subs (str (java.util.UUID/randomUUID)) 0 6))

(defn- time-elapsed [started-at]
  (let [elapsed-us (- (System/currentTimeMillis) started-at)]
    (with-precision 2
      (str (/ (double elapsed-us) 1000) " seconds"))))

(defn- wrap-with-build-logging [build-fn]
  (fn [id? & args]
    (let [started-at (System/currentTimeMillis)
          {:keys [output-to output-dir]} (second args)]
      ;; print start message
      (log/info (str "Compiling build"
                     (when id? (str " " id?))
                     " to \""
                     (or output-to output-dir)))
      (try
        (let [warnings (volatile! [])
              out *out*
              warning-fn (fn [warning-type env extra]
                            (when (get cljs.analyzer/*cljs-warnings* warning-type)
                              (let [warn {:warning-type warning-type
                                          :env env
                                          :extra extra
                                          :path ana/*cljs-file*}]
                                (binding [*out* out]
                                  (if (<= (count @warnings) 2)
                                    (log/cljs-syntax-warning warn)
                                    (binding [log/*syntax-error-style* :concise]
                                      (log/cljs-syntax-warning warn))))
                                (vswap! warnings conj warn))))]
          (binding [cljs.analyzer/*cljs-warning-handlers*
                    (conj (remove #{cljs.analyzer/default-warning-handler}
                                  cljs.analyzer/*cljs-warning-handlers*)
                          warning-fn)]
            (apply build-fn args)))
        (log/succeed (str "Successfully compiled build"
                       (when id? (str " " id?))
                       " to \""
                       (or output-to output-dir)
                       "\" in " (time-elapsed started-at) "."))
        (catch Throwable e
          (log/failure (str
                        "Failed to compile build" (when id? (str " " id?))
                        " in " (time-elapsed started-at) "."))
          (log/syntax-exception e)
          (throw e))))))

(def build-cljs (wrap-with-build-logging bapi/build))
(def fig-core-build (wrap-with-build-logging figwheel.core/build))

(defn watch-build [id inputs opts cenv & [reload-config]]
  (when-let [inputs (if (coll? inputs) inputs [inputs])]
    (log/info "Watching and compiling paths:" (pr-str inputs) "for build -" id)
    (fww/add-watch!
     [::autobuild id]
     (merge
      {::watch-info (merge
                     (:extra-info reload-config)
                     {:id id
                      :paths inputs
                      :options opts
                      :compiler-env cenv
                      :reload-config reload-config})}
      {:paths inputs
       :filter (fww/suffix-filter (into #{"cljs" "js"}
                                        (cond
                                          (coll? (:reload-clj-files reload-config))
                                          (mapv name (:reload-clj-files reload-config))
                                          (false? (:reload-clj-files reload-config)) []
                                          :else ["clj" "cljc"])))
       :handler (fww/throttle
                 (:wait-time-ms reload-config 50)
                 (bound-fn [evts]
                   (binding [cljs.env/*compiler* cenv]
                     (let [files (mapv (comp #(.getCanonicalPath %) :file) evts)
                           inputs (if (coll? inputs) (apply bapi/inputs inputs) inputs)]
                       (try
                         (when-let [clj-files
                                    (not-empty
                                     (filter
                                      #(or (.endsWith % ".clj")
                                           (.endsWith % ".cljc"))
                                      files))]
                           (log/debug "Reloading clj files: " (pr-str (map str clj-files)))
                           (try
                             (figwheel.core/reload-clj-files clj-files)
                             (catch Throwable t
                               (log/syntax-exception t)
                               (figwheel.core/notify-on-exception cenv t {})
                               (throw t))))
                         (log/debug "Detected changed cljs files: " (pr-str (map str files)))
                         (fig-core-build id inputs opts cenv files)
                         ;; exceptions are reported by the time they get to here
                         (catch Throwable t false))))))}))))

(def validate-config!*
  (when (try
          (require 'clojure.spec.alpha)
          (require 'expound.alpha)
          (require 'figwheel.main.schema)
          true
          (catch Throwable t false))
    (resolve 'figwheel.main.schema/validate-config!)))

(defn validate-config! [edn fail-msg & [succ-msg]]
  (when (and validate-config!* (not (false? (:validate-config edn))))
    (validate-config!* edn fail-msg)
    (when succ-msg
      (log/succeed succ-msg))))

;; ----------------------------------------------------------------------------
;; Additional cli options
;; ----------------------------------------------------------------------------

;; Help


(def help-template
  "Usage: clojure -m figwheel.main [init-opt*] [main-opt] [arg*]

Common usage:
  clj -m figwheel.main -b dev -r
Which is equivalient to:
  clj -m figwheel.main -co dev.cljs.edn -c example.core -r

In the above example, dev.cljs.edn is a file in the current directory
that holds a build configuration which is a Map of ClojureScript
compile options. In the above command example.core is ClojureScript
namespace on your classpath that you want to compile.

A minimal dev.cljs.edn will look similar to:
{:main example.core}

The above command will start a watch process that will compile your
source files when one of them changes, it will also facilitate
communication between this watch process and your JavaScript
environment (normally a browser window) so that it can hot reload
changed code into the environment. After the initial compile, it
will then launch a browser to host your compiled ClojureScript code,
and finally a CLJS REPL will launch.

Configuration:

In the above example, besides looking for a dev.cljs.edn file,
figwheel.main will also look for a figwheel-main.edn file in the
current directory as well.

A list of all the config options can be found here:
https://github.com/bhauman/lein-figwheel/blob/master/figwheel-main/doc/figwheel-main-options.md

A list of ClojureScript compile options can be found here:
https://clojurescript.org/reference/compiler-options

You can add build specific figwheel.main configuration in the
*.cljs.edn file by adding metadata to the build config file like
so:

^{:watch-dirs [\"dev\" \"cljs-src\"]}
{:main example.core}

Command Line Options:

With no options or args, figwheel.main runs a ClojureScript REPL

%s
For --main and --repl:

  - Enters the cljs.user namespace
  - Binds *command-line-args* to a seq of strings containing command line
    args that appear after any main option
  - Runs all init options in order
  - Calls a -main function or runs a repl or script if requested

The init options may be repeated and mixed freely, but must appear before
any main option.

In the case of --compile and --build you may supply --repl or --serve
options afterwards.

Paths may be absolute or relative in the filesystem or relative to
classpath. Classpath-relative paths have prefix of @ or @/")

(defn adjust-option-docs [commands]
  (-> commands
      (update-in [:groups :cljs.cli/main&compile :pseudos]
                 dissoc ["-re" "--repl-env"])
      (assoc-in [:init ["-d" "--output-dir"] :doc]
                "Set the output directory to use")
      (update-in [:init ["-w" "--watch"] :doc] str
                ". This option can be supplied multiple times.")))

(defn help-str [repl-env]
  (format
   help-template
   (#'cljs.cli/options-str
    (adjust-option-docs
     (#'cljs.cli/merged-commands repl-env)))))

(defn help-opt
  [repl-env _ _]
  (println (help-str repl-env)))

;; safer option reading from files which prints out syntax errors

(defn read-edn-file [f]
  (try (redn/read
        (rtypes/source-logging-push-back-reader (io/reader f) 1 f))
       (catch Throwable t
         (log/syntax-exception t)
         (throw
          (ex-info (str "Couldn't read the file:" f)
                   {::error true} t)))))

(defn read-edn-string [s & [fail-msg]]
  (try
    (redn/read
     (rtypes/source-logging-push-back-reader (io/reader (.getBytes s)) 1))
    (catch Throwable t
      (let [except-data (fig-ex/add-excerpt (fig-ex/parse-exception t) s)]
        (log/info (ansip/format-str (log/format-ex except-data)))
        (throw (ex-info (str (or fail-msg "Failed to read EDN string: ")
                             (.getMessage t))
                        {::error true}
                        t))))))

(defn read-edn-opts [str]
  (letfn [(read-rsrc [rsrc-str orig-str]
            (if-let [rsrc (io/resource rsrc-str)]
              (read-edn-string (slurp rsrc))
              (cljs.cli/missing-resource orig-str)))]
    (cond
     (string/starts-with? str "@/") (read-rsrc (subs str 2) str)
     (string/starts-with? str "@") (read-rsrc (subs str 1) str)
     :else
     (let [f (io/file str)]
       (if (.isFile f)
         (read-edn-file f)
         (cljs.cli/missing-file str))))))

(defn merge-meta [m m] (with-meta (merge m m) (merge (meta m) (meta m))))

(defn load-edn-opts [str]
  (reduce merge-meta {} (map read-edn-opts (cljs.util/split-paths str))))

(defn fallback-id [edn]
  (let [m (meta edn)]
    (cond
      (and (:id m) (not (string/blank? (str (:id m)))))
      (:id m)
      ;;(:main edn)      (munge (str (:main edn)))
      :else
      (str "build-"
           (.getValue (doto (java.util.zip.CRC32.)
                        (.update (.getBytes (pr-str (into (sorted-map) edn))))))))))

(defn compile-opts-opt
  [cfg copts]
  (let [copts (string/trim copts)
        edn   (if (or (string/starts-with? copts "{")
                      (string/starts-with? copts "^"))
                (read-edn-string copts "Error reading EDN from command line flag: -co ")
                (load-edn-opts copts))
        config  (meta edn)
        id
        (and edn
             (if (or (string/starts-with? copts "{")
                     (string/starts-with? copts "^"))
               (and (map? edn) (fallback-id edn))
               (->>
                (cljs.util/split-paths copts)
                (filter (complement string/blank?))
                (filter #(not (.startsWith % "@")))
                (map io/file)
                (map (comp first #(string/split % #"\.") #(.getName %)))
                (string/join ""))))]
    (cond-> cfg
      edn (update :options merge edn)
      id  (update-in [::build :id] #(if-not % id %))
      config (update-in [::build :config] merge config))))

(defn figwheel-opts-opt
  [cfg ropts]
  (let [ropts (string/trim ropts)
        edn   (if (string/starts-with? ropts "{")
                (read-edn-string ropts "Error reading EDN from command line flag: -fwo ")
                (load-edn-opts ropts))]
    (validate-config! edn "Error validating figwheel options EDN provided to -fwo CLI flag")
    (update cfg ::config merge edn)))

(defn print-config-opt [cfg opt]
  (assoc-in cfg [::config :pprint-config] (not (#{"false"} opt))))

(defn- watch-opt
  [cfg path]
  (when-not (.exists (io/file path))
    (if (or (string/starts-with? path "-")
            (string/blank? path))
      (throw
        (ex-info
          (str "Missing watch path")
          {:cljs.main/error :invalid-arg}))
      (throw
        (ex-info
          (str "Watch path \"" path "\" does not exist")
          {:cljs.main/error :invalid-arg}))))
  (update-in cfg [::extra-config :watch-dirs] (fnil conj []) path))

(defn figwheel-opt [cfg bl]
  (assoc-in cfg [::config :figwheel-core] (not= bl "false")))



(defn get-build [bn]
  (let [fname (if (.contains bn (System/getProperty "path.separator"))
                bn
                (str bn ".cljs.edn"))
        build (->> (cljs.util/split-paths bn)
                   (map #(str % ".cljs.edn"))
                   (string/join (System/getProperty "path.separator"))
                   load-edn-opts)]
    (when (meta build)
      (when-not (false? (:validate-config (meta build)))
        (log/debug "Validating metadata in build option: " fname)
        (validate-config! (meta build) (str "Configuration error in build options meta data:" fname))))
    build))

(defn watch-dir-from-ns [main-ns]
  (let [source (bapi/ns->location main-ns)]
    (when-let [f (:uri source)]
      (when (= "file" (.getScheme (.toURI f)))
        (let [res (fw-util/relativized-path-parts (.getPath f))
              end-parts (fw-util/path-parts (:relative-path source))]
          (when (= end-parts (take-last (count end-parts) res))
            (str (apply io/file (drop-last (count end-parts) res)))))))))

(def default-main-repl-index-body
  (str
   "<p>Welcome to the Figwheel REPL page.</p>"
   "<p>This page is served when you launch <code>figwheel.main</code> without any command line arguments.</p>"
   "<p>This page is currently hosting your REPL and application evaluation environment. "
   "Validate the connection by typing <code>(js/alert&nbsp;\"Hello&nbsp;Figwheel!\")</code> in the REPL.</p>"))

(defn build-opt [cfg bn]
  (when-not (.exists (io/file (str bn ".cljs.edn")))
    (if (or (string/starts-with? bn "-")
            (string/blank? bn))
      (throw
        (ex-info
          (str "Missing build name")
          {:cljs.main/error :invalid-arg}))
      (throw
        (ex-info
          (str "Build " (str bn ".cljs.edn") " does not exist")
          {:cljs.main/error :invalid-arg}))))
  (let [options (get-build bn)]
    (-> cfg
        (update :options merge options)
        (assoc  ::build (cond-> {:id bn}
                          (meta options)
                          (assoc :config (meta options)))))))

(defn build-once-opt [cfg bn]
  (let [cfg (build-opt cfg bn)]
    (assoc-in cfg [::config :mode] :build-once)))

(defn background-build-opt [cfg bn]
  (let [{:keys [options ::build]} (build-opt {} bn)]
    (update cfg ::background-builds
            (fnil conj [])
            (assoc build :options options))))

;; TODO move these down to main action section

(declare default-compile)

(defn build-main-opt [repl-env-fn [_ build-name & args] cfg]
  ;; serve if no other args
  (let [args (if-not (#{"-s" "-r" "--repl" "--serve"} (first args))
               (cons "-s" args)
               args)]
    (default-compile repl-env-fn
                   (merge (build-opt cfg build-name)
                          {:args args
                           ::build-main-opt true}))))

(defn build-once-main-opt [repl-env-fn [_ build-name & args] cfg]
  (default-compile repl-env-fn
                   (merge (build-once-opt cfg build-name)
                          {:args args})))

(declare default-output-dir default-output-to)

(defn repl-main-opt [repl-env-fn args cfg]
  (let [target-on-classpath?
        (when-let [target-dir
                   (:target-dir
                    (try (read-string (slurp "figwheel-main.edn"))
                         (catch Throwable t
                           nil))
                    "target")]
          (fw-util/dir-on-classpath? target-dir))
        temp-dir (when-not (or (= :nodejs (:target (:options cfg)))
                               target-on-classpath?)
                   (let [tempf (java.io.File/createTempFile "figwheel" "repl")]
                     (.delete tempf)
                     (.mkdirs tempf)
                     (.deleteOnExit (io/file tempf))
                     (fw-util/add-classpath! (.toURL (.toURI tempf)))
                     tempf))]
    (default-compile
     repl-env-fn
     (-> cfg
         (cond->
             temp-dir (assoc-in [:options :output-dir]
                                (default-output-dir
                                 (assoc cfg [::config :target-dir] temp-dir)))
             temp-dir (assoc-in [:options :output-to]
                                (default-output-to
                                 (assoc cfg [::config :target-dir] temp-dir)))
             temp-dir (assoc-in [:options :asset-path]  "cljs-out"))
         (assoc :args args)
         (update :options (fn [opt] (merge {:main 'figwheel.repl.preload} opt)))
         (assoc-in [:options :aot-cache] true)
         (assoc-in [:repl-env-options :open-url]
                   "http://[[server-hostname]]:[[server-port]]/?figwheel-server-force-default-index=true")
         ;; TODO :default-index-body should be a function that takes the build options as an arg
         (assoc-in [:repl-env-options :default-index-body] default-main-repl-index-body)
         (assoc-in [::config :mode] :repl)
         (assoc-in [::build] {:id "figwheel-default-repl-build"})))))

(declare serve update-config)

(defn print-conf [cfg]
  (println "---------------------- Figwheel options ----------------------")
  (pprint (::config cfg))
  (println "---------------------- Compiler options ----------------------")
  (pprint (:options cfg)))

(defn serve-main-opt [repl-env-fn args b-cfg]
  (let [{:keys [::config repl-env-options repl-options] :as cfg}
        (-> b-cfg
            (assoc :args args)
            update-config
            (assoc-in [:repl-env-options :default-index-body]
                      ;; TODO helpful instructions on where to put index.html
                      "<center><h3 style=\"color:red;\">index.html not found</h3></center>"))
        {:keys [pprint-config]} config
        repl-env (apply repl-env-fn (mapcat identity repl-env-options))]
    (log/trace "Verbose config:" (with-out-str (pprint cfg)))
    (if pprint-config
      (do
        (log/info ":pprint-config true - printing config:")
        (print-conf cfg))
      (serve {:repl-env repl-env
              :repl-options repl-options
              :join? true}))))

(def figwheel-commands
  {:init {["-w" "--watch"]
          {:group :cljs.cli/compile :fn watch-opt
           :arg "path"
           :doc "Continuously build, only effective with the --compile and --build main options"}
          ["-fwo" "--fw-opts"]
          {:group :cljs.cli/compile :fn figwheel-opts-opt
           :arg "edn"
           :doc (str "Options to configure figwheel.main, can be an EDN string or "
                     "system-dependent path-separated list of EDN files / classpath resources. Options "
                     "will be merged left to right.")}
          ["-co" "--compile-opts"]
          {:group :cljs.cli/main&compile :fn compile-opts-opt
           :arg "edn"
           :doc (str "Options to configure the build, can be an EDN string or "
                     "system-dependent path-separated list of EDN files / classpath resources. Options "
                     "will be merged left to right. Any meta data will be merged with the figwheel-options.")}
          ;; TODO uncertain about this
          ["-fw" "--figwheel"]
          {:group :cljs.cli/compile :fn figwheel-opt
           :arg "bool"
           :doc (str "Use Figwheel to auto reload and report compile info. "
                     "Only takes effect when watching is happening and the "
                     "optimizations level is :none or nil."
                     "Defaults to true.")}
          ["-bb" "--background-build"]
          {:group :cljs.cli/compile :fn background-build-opt
           :arg "str"
           :doc "The name of a build config to watch and build in the background."}
          ["-pc" "--print-config"]
          {:group :cljs.cli/compile :fn print-config-opt
           :doc "Instead of running the command print out the configuration built up by the command. Useful for debugging."}
          }
   :main {["-b" "--build"]
          {:fn build-main-opt
           :arg "string"
           :doc (str "Run a compile. The supplied build name refers to a  "
                     "compililation options edn file. IE. \"dev\" will indicate "
                     "that a \"dev.cljs.edn\" will be read for "
                     "compilation options. The --build option will make an "
                     "extra attempt to "
                     "initialize a figwheel live reloading workflow. "
                     "If --repl follows, "
                     "will launch a REPL after the compile completes. "
                     "If --server follows, will start a web server according to "
                     "current configuration after the compile "
                     "completes.")}
          ["-bo" "--build-once"]
          {:fn build-once-main-opt
           :arg "string"
           :doc (str "Compile for the build name one time. "
                     "Looks for a build EDN file just like the --build command.")}
          ["-r" "--repl"]
          {:fn repl-main-opt
           :doc "Run a REPL"}
          ["-s" "--serve"]
          {:fn serve-main-opt
           :arg "host:port"
           :doc "Run a server based on the figwheel-main configuration options."}
          ["-h" "--help" "-?"]
          {:fn help-opt
           :doc "Print this help message and exit"}
          }})

;; ----------------------------------------------------------------------------
;; Config
;; ----------------------------------------------------------------------------

(defn default-output-dir* [target & [scope]]
  (->> (cond-> [(or target "target") "public" "cljs-out"]
         scope (conj scope))
       (apply io/file)
       (.getPath)))

(defmulti default-output-dir (fn [{:keys [options]}]
                               (get options :target :browser)))

(defmethod default-output-dir :default [{:keys [::config ::build]}]
  (default-output-dir* (:target-dir config) (:id build)))

(defmethod default-output-dir :nodejs [{:keys [::config ::build]}]
  (let [target (:target-dir config "target")
        scope (:id build)]
    (->> (cond-> [target "node"]
           scope (conj scope))
         (apply io/file)
         (.getPath))))

(defn default-output-to* [target & [scope]]
  (.getPath (io/file (or target "target") "public" "cljs-out"
                     (cond->> "main.js"
                       scope (str scope "-")))))

(defmulti default-output-to (fn [{:keys [options]}]
                              (get options :target :browser)))

(defmethod default-output-to :default [{:keys [::config ::build]}]
  (default-output-to* (:target-dir config) (:id build)))

(defmethod default-output-to :nodejs [{:keys [::build] :as cfg}]
  (let [scope (:id build)]
    (.getPath (io/file (default-output-dir cfg)
                       (cond->> "main.js"
                         scope (str scope "-"))))))

(defn extra-config-merge [a' b']
  (merge-with (fn [a b]
                (cond
                  (and (map? a) (map? b)) (merge a b)
                  (and (sequential? a)
                       (sequential? b))
                  (distinct (concat a b))
                  (nil? b) a
                  :else b))
              a' b'))

(defn process-figwheel-main-edn [{:keys [ring-handler] :as main-edn}]
  (when-not (false? (:validate-config main-edn))
    (log/info "Validating figwheel-main.edn")
    (validate-config! main-edn "Configuration error in figwheel-main.edn"
                      "figwheel-main.edn is valid!"))

  (let [handler (and ring-handler (fw-util/require-resolve-var ring-handler))]
    (when (and ring-handler (not handler))
      (throw (ex-info "Unable to find :ring-handler" {:ring-handler ring-handler})))
    (cond-> main-edn
      handler (assoc :ring-handler handler))))



;; use tools reader read-string for better error messages
#_(redn/read-string)
(defn fetch-figwheel-main-edn [cfg]
  (read-edn-file "figwheel-main.edn"))

(defn- config-figwheel-main-edn [cfg]
  (if-not (.isFile (io/file "figwheel-main.edn"))
    cfg
    (let [config-edn (or (::start-figwheel-options cfg)
                         (process-figwheel-main-edn
                          (fetch-figwheel-main-edn cfg)))]
      (-> cfg
          (update ::config #(merge config-edn %))))))

(defn- config-merge-current-build-conf [{:keys [::extra-config ::build] :as cfg}]
  (update cfg
          ::config #(extra-config-merge
                     (merge-with (fn [a b] (if b b a)) % (:config build))
                     extra-config)))

(defn host-port-arg? [arg]
  (and arg (re-matches #"(.*):(\d*)" arg)))

(defn update-server-host-port [config [f address-port & args]]
  (if (and (#{"-s" "--serve"} f) address-port)
    (let [[_ host port] (host-port-arg? address-port)]
      (cond-> config
        (not (string/blank? host)) (assoc-in [:ring-server-options :host] host)
        (not (string/blank? port)) (assoc-in [:ring-server-options :port] (Integer/parseInt port))))
    config))

;; targets options
(defn- config-main-ns [{:keys [ns options] :as cfg}]
  (let [main-ns (if (and ns (not (#{"-r" "--repl" "-s" "--serve"} ns)))
                  (symbol ns)
                  (:main options))]
    (cond-> cfg
      main-ns (assoc :ns main-ns)       ;; TODO not needed?
      main-ns (assoc-in [:options :main] main-ns))))

;; targets local config
(defn- config-repl-serve? [{:keys [ns args] :as cfg}]
  (let [rfs      #{"-r" "--repl"}
        sfs      #{"-s" "--serve"}]
    (cond-> cfg
      (boolean (or (rfs ns) (rfs (first args))))
      (assoc-in [::config :mode] :repl)
      (boolean (or (sfs ns) (sfs (first args))))
      (->
       (assoc-in [::config :mode] :serve)
       (update ::config update-server-host-port args))
      (rfs (first args))
      (update :args rest)
      (sfs (first args))
      (update :args rest)
      (and (sfs (first args)) (host-port-arg? (second args)))
      (update :args rest))))

;; targets local config
(defn- config-update-watch-dirs [{:keys [options ::config] :as cfg}]
  ;; remember we have to fix this for the repl-opt fn as well
  ;; so that it understands multiple watch directories
  (update-in cfg [::config :watch-dirs]
            #(not-empty
              (distinct
               (let [ns-watch-dir (and
                                   (not (:watch options))
                                   (empty? %)
                                   (:main options)
                                   (watch-dir-from-ns (:main options)))]
                 (cond-> %
                   (:watch options) (conj (:watch options))
                   ns-watch-dir (conj ns-watch-dir)))))))

;; needs local config
(defn figwheel-mode? [{:keys [::config options]}]
  (and (:figwheel-core config true)
       (and (#{:repl :serve} (:mode config))
            (not-empty (:watch-dirs config)))
       (= :none (:optimizations options :none))))

(defn repl-connection? [{:keys [::config options] :as cfg}]
  (or (and (#{:repl :main} (:mode config))
           (= :none (:optimizations options :none)))
      (figwheel-mode? cfg)))

;; TODO this is a no-op right now
(defn prep-client-config [config]
  (let [cl-config (select-keys config [])]
    cl-config))

;; targets options needs local config
(defn- config-figwheel-mode? [{:keys [::config options] :as cfg}]
  (cond-> cfg
    ;; check for a main??
    (figwheel-mode? cfg)
    (update-in [:options :preloads]
               (fn [p]
                 (vec (distinct
                       (concat p '[figwheel.core figwheel.main])))))
    (false? (:heads-up-display config))
    (update-in [:options :closure-defines] assoc 'figwheel.core/heads-up-display false)
    (true? (:load-warninged-code config))
    (update-in [:options :closure-defines] assoc 'figwheel.core/load-warninged-code true)))

;; targets options
;; TODO needs to consider case where one or the other is specified???
(defn- config-default-dirs [{:keys [options ::config ::build] :as cfg}]
  (cond-> cfg
    (nil? (:output-to options))
    (assoc-in [:options :output-to] (default-output-to cfg))
    (nil? (:output-dir options))
    (assoc-in [:options :output-dir] (default-output-dir cfg))))

(defn figure-default-asset-path [{:keys [figwheel-options options ::config ::build] :as cfg}]

  (if (= :nodejs (:target options))
    (:output-dir options)
    (let [{:keys [output-dir]} options]
      ;; TODO could discover the resource root if there is only one
      ;; or if ONLY static file serving can probably do something with that
      ;; as well
      ;; UNTIL THEN if you have configured your static resources no default asset-path
      (when-not (contains? (:ring-stack-options figwheel-options) :static)
        (let [parts (fw-util/relativized-path-parts (or output-dir
                                                        (default-output-dir cfg)))]
          (when-let [asset-path
                     (->> parts
                          (split-with (complement #{"public"}))
                          last
                          rest
                          not-empty)]
            (str (apply io/file asset-path))))))))

;; targets options
(defn- config-default-asset-path [{:keys [options] :as cfg}]
  (cond-> cfg
    (nil? (:asset-path options))
    (assoc-in [:options :asset-path] (figure-default-asset-path cfg))))

;; targets options
(defn- config-default-aot-cache-false [{:keys [options] :as cfg}]
  (cond-> cfg
    (not (contains? options :aot-cache))
    (assoc-in [:options :aot-cache] false)))

(defn config-clean [cfg]
  (update cfg :options dissoc :watch))

;; TODO create connection

(let [localhost (promise)]
  ;; this call takes a very long time to complete so lets get in in parallel
  (doto (Thread. #(deliver localhost (java.net.InetAddress/getLocalHost)))
    (.setDaemon true)
    (.start))
  (defn fill-connect-url-template [url host server-port]
    (cond-> url
      (.contains url "[[config-hostname]]")
      (string/replace "[[config-hostname]]" (or host "localhost"))

      (.contains url "[[server-hostname]]")
      (string/replace "[[server-hostname]]" (.getHostName @localhost))

      (.contains url "[[server-ip]]")
      (string/replace "[[server-ip]]"       (.getHostAddress @localhost))

      (.contains url "[[server-port]]")
      (string/replace "[[server-port]]"     (str server-port)))))

(defn add-to-query [uri query-map]
  (let [[pre query] (string/split uri #"\?")]
    (str pre
         (when (or query (not-empty query-map))
             (str "?"
              (string/join "&"
                           (map (fn [[k v]]
                                  (str (name k)
                                       "="
                                       (java.net.URLEncoder/encode (str v) "UTF-8")))
                                query-map))
              (when (not (string/blank? query))
                (str "&" query)))))))

#_(add-to-query "ws://localhost:9500/figwheel-connect?hey=5" {:ab 'ab})

(defn config-connect-url [{:keys [::config repl-env-options] :as cfg} connect-id]
  (let [port (get-in config [:ring-server-options :port] figwheel.repl/default-port)
        host (get-in config [:ring-server-options :host] "localhost")
        connect-url
        (fill-connect-url-template
         (:connect-url config "ws://[[config-hostname]]:[[server-port]]/figwheel-connect")
         host
         port)]
    (add-to-query connect-url connect-id)))

#_(config-connect-url {} {:abb 1})

(defn config-repl-connect [{:keys [::config options ::build] :as cfg}]
  (let [connect-id (:connect-id config
                                (cond-> {:fwprocess process-unique}
                                  (:id build) (assoc :fwbuild (:id build))))
        conn-url (config-connect-url cfg connect-id)
        conn? (repl-connection? cfg)]
    (cond-> cfg
      conn?
      (update-in [:options :closure-defines] assoc 'figwheel.repl/connect-url conn-url)
      conn?
      (update-in [:options :preloads]
                 (fn [p]
                   (vec (distinct
                         (concat p '[figwheel.repl.preload])))))
      (and conn? (:client-print-to config))
      (update-in [:options :closure-defines] assoc
                 'figwheel.repl/print-output
                 (string/join "," (distinct (map name (:client-print-to config)))))
      (and conn? (not-empty connect-id))
      (assoc-in [:repl-env-options :connection-filter]
                (let [kys (keys connect-id)]
                  (fn [{:keys [query]}]
                    (= (select-keys query kys)
                       connect-id)))))))

(defn config-open-file-command [{:keys [::config options] :as cfg}]
  (if-let [setup (and (:open-file-command config)
                      (repl-connection? cfg)
                      (fw-util/require-resolve-var 'figwheel.main.editor/setup))]
    (-> cfg
        (update ::initializers (fnil conj []) #(setup (:open-file-command config)))
        (update-in [:options :preloads]
                   (fn [p] (vec (distinct (conj p 'figwheel.main.editor))))))
    cfg))

(defn watch-css [css-dirs]
  (when-let [css-dirs (not-empty css-dirs)]
    (when-let [start-css (fw-util/require-resolve-var 'figwheel.main.css-reload/start*)]
      (start-css css-dirs))))

(defn config-watch-css [{:keys [::config options] :as cfg}]
  (cond-> cfg
    (and (not-empty (:css-dirs config))
         (repl-connection? cfg))
    (->
     (update ::initializers (fnil conj []) #(watch-css (:css-dirs config)))
     (update-in [:options :preloads]
                (fn [p] (vec (distinct (conj p 'figwheel.main.css-reload))))))))

(defn get-repl-options [{:keys [options args inits repl-options] :as cfg}]
  (assoc (merge (dissoc options :main)
                repl-options)
         :inits
         (into
          [{:type :init-forms
            :forms (when-not (empty? args)
                     [`(set! *command-line-args* (list ~@args))])}]
          inits)))

(defn get-repl-env-options [{:keys [repl-env-options ::config] :as cfg}]
  (let [repl-options (get-repl-options cfg)]
    (merge
     (select-keys config
                  [:ring-server
                   :ring-server-options
                   :ring-stack
                   :ring-stack-options
                   :ring-handler
                   :launch-node
                   :inspect-node
                   :node-command
                   :broadcast])
     repl-env-options ;; from command line
     (select-keys repl-options [:output-to :output-dir]))))

(defn config-finalize-repl-options [cfg]
  (let [repl-options (get-repl-options cfg)
        repl-env-options (get-repl-env-options cfg)]
    (assoc cfg
           :repl-options repl-options
           :repl-env-options repl-env-options)))

(defn config-set-log-level! [{:keys [::config] :as cfg}]
  (when-let [log-level (:log-level config)]
    (log/set-level log-level))
  cfg)

(defn config-ansi-color-output! [{:keys [::config] :as cfg}]
  (when (some? (:ansi-color-output config))
    (alter-var-root #'ansip/*use-color* (fn [_] (:ansi-color-output config))))
  cfg)

(defn config-log-syntax-error-style! [{:keys [::config] :as cfg}]
  (when (some? (:log-syntax-error-style config))
    (alter-var-root #'log/*syntax-error-style* (fn [_] (:log-syntax-error-style config))))
  cfg)

#_(config-connect-url {::build-name "dev"})

(defn update-config [cfg]
  (->> cfg
       config-figwheel-main-edn
       config-merge-current-build-conf
       config-ansi-color-output!
       config-set-log-level!
       config-log-syntax-error-style!
       config-repl-serve?
       config-main-ns
       config-update-watch-dirs
       config-figwheel-mode?
       config-default-dirs
       config-default-asset-path
       config-default-aot-cache-false
       config-repl-connect
       config-open-file-command
       config-watch-css
       config-finalize-repl-options
       config-clean))

;; ----------------------------------------------------------------------------
;; Main action
;; ----------------------------------------------------------------------------

(defn build [{:keys [watch-dirs mode ::build] :as config} options cenv]
  (let [source (when (and (= :none (:optimizations options :none)) (:main options))
                 (:uri (bapi/ns->location (symbol (:main options)))))
        id (:id (::build *config*) "dev")]
    ;; TODO should probably try obtain a watch path from :main here
    ;; if watch-dirs is empty
    (if-let [paths (and (not= mode :build-once) (not-empty watch-dirs))]
      (do
        (build-cljs id (apply bapi/inputs paths) options cenv)
        (watch-build id paths options cenv (select-keys config [:reload-clj-files :wait-time-ms])))
      (cond
        source
        (build-cljs id source options cenv)
        ;; TODO need :compile-paths config param
        (not-empty watch-dirs)
        (build-cljs id (apply bapi/inputs watch-dirs) options cenv)))))

(defn log-server-start [repl-env]
  (let [host (get-in repl-env [:ring-server-options :host] "localhost")
        port (get-in repl-env [:ring-server-options :port] figwheel.repl/default-port)
        scheme (if (get-in repl-env [:ring-server-options :ssl?])
                 "https" "http")]
    (log/info (str "Starting Server at " scheme "://" host ":" port ))))

(defn start-file-logger []
  (when-let [log-fname (and (bound? #'*config*) (get-in *config* [::config :log-file]))]
    (log/info "Redirecting log ouput to file:" log-fname)
    (io/make-parents log-fname)
    (log/switch-to-file-handler! log-fname)))

;; ------------------------------
;; REPL
;; ------------------------------

(defn bound-var? [sym]
  (when-let [v (resolve sym)]
    (thread-bound? v)))

(defn in-nrepl? [] (bound-var? 'clojure.tools.nrepl.middleware.interruptible-eval/*msg*))

(defn nrepl-repl [repl-env repl-options]
  (if-let [piggie-repl (or (and (bound-var? 'cider.piggieback/*cljs-repl-env*)
                                (resolve 'cider.piggieback/cljs-repl))
                           (and (bound-var? 'cemerick.piggieback/*cljs-repl-env*)
                                (resolve 'cemerick.piggieback/cljs-repl)))]
    (apply piggie-repl repl-env (mapcat identity repl-options))
    (throw (ex-info "Failed to launch Figwheel CLJS REPL: nREPL connection found but unable to load piggieback.
This is commonly caused by
 A) not providing piggieback as a dependency and/or
 B) not adding piggieback middleware into your nrepl middleware chain.
Please see the documentation for piggieback here https://github.com/clojure-emacs/piggieback#installation

Note: Cider will inject this config into your project.clj.
This can cause confusion when your are not using Cider."
                    {::error :no-cljs-nrepl-middleware}))))

(defn repl-caught [err repl-env repl-options]
  (let [root-source-info (some-> err ex-data :root-source-info)]
    (if (and (instance? clojure.lang.IExceptionInfo err)
             (#{:js-eval-error :js-eval-exception} (:type (ex-data err))))
      (try
        (cljs.repl/repl-caught err repl-env repl-options)
        (catch Throwable e
          (let [{:keys [value stacktrace] :as data} (ex-data err)]
            (when value
              (println value))
            (when stacktrace
              (println stacktrace))
            (log/debug (with-out-str (pprint data))))))
      (let [except-data (fig-ex/add-excerpt (fig-ex/parse-exception err))]
        ;; TODO strange ANSI color error when printing this inside rebel-readline
        (println (binding [ansip/*use-color* (if (resolve 'rebel-readline.cljs.repl/repl*)
                                               false
                                               ansip/*use-color*)]
                   (ansip/format-str (log/format-ex except-data))))
        #_(clojure.pprint/pprint (Throwable->map err))
        (flush)))))

;; TODO this needs to work in nrepl as well
(defn repl [repl-env repl-options]
  (log-server-start repl-env)
  (log/info "Starting REPL")
  ;; when we have a logging file start log here
  (start-file-logger)
  (binding [cljs.analyzer/*cljs-warning-handlers*
            (conj (remove #{cljs.analyzer/default-warning-handler}
                          cljs.analyzer/*cljs-warning-handlers*)
                  (fn [warning-type env extra]
                    (when (get cljs.analyzer/*cljs-warnings* warning-type)
                      (->> {:warning-type warning-type
                            :env env
                            :extra extra
                            :path ana/*cljs-file*}
                           figwheel.core/warning-info
                           (fig-ex/root-source->file-excerpt (:root-source-info env))
                           log/format-ex
                           ansip/format-str
                           string/trim-newline
                           println)
                      (flush))))]
    (let [repl-options (assoc repl-options :caught (:caught repl-options repl-caught))]
      (if (in-nrepl?)
        (nrepl-repl repl-env repl-options)
        (let [repl-fn (or (when-not (false? (:rebel-readline (::config *config*)))
                            (fw-util/require-resolve-var 'rebel-readline.cljs.repl/repl*))
                          cljs.repl/repl*)]
          (try
            (repl-fn repl-env repl-options)
            (catch clojure.lang.ExceptionInfo e
              (if (-> e ex-data :type (= :rebel-readline.jline-api/bad-terminal))
                (do (println (.getMessage e))
                    (cljs.repl/repl* repl-env repl-options))
                (throw e)))))))))

(defn serve [{:keys [repl-env repl-options eval-str join?]}]
  (log-server-start repl-env)
  (cljs.repl/-setup repl-env repl-options)
  (when eval-str
    (cljs.repl/evaluate-form repl-env
                             (assoc (ana/empty-env)
                                    :ns (ana/get-namespace ana/*cljs-ns*))
                             "<cljs repl>"
                             ;; todo allow opts to be added here
                             (first (ana-api/forms-seq (StringReader. eval-str)))))

  (when-let [server (and join? @(:server repl-env))]
    (.join server)))

(defn background-build [cfg {:keys [id config options]}]
  (let [{:keys [::build ::config repl-env-options] :as cfg}
        (-> (select-keys cfg [::start-figwheel-options])
            (assoc :options options
                   ::build {:id id :config config})
            update-config)
        cenv (cljs.env/default-compiler-env)]
    (when (empty? (:watch-dirs config))
          (log/failure "Can not watch a build with no :watch-dirs"))
    (when (not-empty (:watch-dirs config))
      (log/info "Starting background autobuild - " (:id build))
      (binding [cljs.env/*compiler* cenv]
        (build-cljs (:id build) (apply bapi/inputs (:watch-dirs config)) (:options cfg) cenv)
        (watch-build (:id build)
                     (:watch-dirs config)
                     (:options cfg)
                     cenv
                     (select-keys config [:reload-clj-files :wait-time-ms]))
        ;; TODO need to move to this pattern instead of repl evals
        (when (first (filter #{'figwheel.core} (:preloads (:options cfg))))
          (binding [cljs.repl/*repl-env* (figwheel.repl/repl-env*
                                          (select-keys repl-env-options
                                                       [:connection-filter]))
                    figwheel.core/*config* (select-keys config [:hot-reload-cljs :broadcast-reload])]
            (figwheel.core/start*)))))))

(defn start-background-builds [{:keys [::background-builds] :as cfg}]
  (doseq [build background-builds]
    (background-build cfg build)))

(defn validate-fix-target-classpath! [{:keys [::config ::build options]}]
  (when (#{nil :browser} (:target options))
    (when-not (contains? (:ring-stack-options config) :static)
      (when-let [output-to (:output-to options)]
        (when-not (.isAbsolute (io/file output-to))
          (let [parts (fw-util/path-parts output-to)
                target-dir (first (split-with (complement #{"public"}) parts))]
            (when (some #{"public"} parts)
              (when-not (empty? target-dir)
                (let [target-dir (apply io/file target-dir)]
                  (when-not (fw-util/dir-on-classpath? target-dir)
                    (log/warn (ansip/format-str
                               [:yellow "Target directory " (pr-str (str target-dir))
                                " is not on the classpath"]))
                    (log/warn "Please fix this by adding" (pr-str (str target-dir))
                              "to your classpath\n"
                              "I.E.\n"
                              "For Clojure CLI Tools in your deps.edn file:\n"
                              "   ensure " (pr-str (str target-dir))
                              "is in your :paths key\n\n"
                              "For Leiningen in your project.clj:\n"
                              "   either set your :target key to" (pr-str (str target-dir))
                              "or add it to the :resource-paths key\n")
                    (log/warn (ansip/format-str [:yellow "Attempting to dynamically add classpath!!"]))
                    (.mkdirs target-dir)
                    (fw-util/add-classpath! (.toURL (.toURI target-dir)))))))))))))

(defn default-main [repl-env-fn cfg]
  (let [target-on-classpath?
        (when-let [target-dir
                   (:target-dir
                    (try (read-string (slurp "figwheel-main.edn"))
                         (catch Throwable t
                           nil))
                    "target")]
          (fw-util/dir-on-classpath? target-dir))
        temp-dir (when-not (or (= :nodejs (:target (:options cfg)))
                               target-on-classpath?)
                   (let [tempf (java.io.File/createTempFile "figwheel" "repl")]
                     (.delete tempf)
                     (.mkdirs tempf)
                     (.deleteOnExit (io/file tempf))
                     (fw-util/add-classpath! (.toURL (.toURI tempf)))
                     tempf))
        cfg (-> cfg
                (cond->
                    temp-dir (assoc-in [:options :output-dir]
                                       (default-output-dir* temp-dir))
                    temp-dir (assoc-in [:options :output-to]
                                       (default-output-to* temp-dir))
                    temp-dir (assoc-in [:options :asset-path]  "cljs-out"))
                (assoc-in [:options :aot-cache] false)
                (update :options #(assoc % :main
                                         (or (some-> (:main cfg) symbol)
                                             'figwheel.repl.preload)))
                (assoc-in [:repl-env-options :open-url]
                          "http://[[server-hostname]]:[[server-port]]/?figwheel-server-force-default-index=true")
                ;; TODO :default-index-body should be a function that takes the build options as an arg
                (assoc-in [:repl-env-options :default-index-body] default-main-repl-index-body)
                (assoc-in [::config :mode] :repl)
                (assoc-in [::build] {:id "figwheel-main-option-build"}))
        source (:uri (bapi/ns->location (get-in cfg [:options :main])))]
    (let [{:keys [options repl-options repl-env-options ::config] :as b-cfg}
          (update-config cfg)
          {:keys [mode pprint-config]} config]
      (if pprint-config
        (do
          (log/info ":pprint-config true - printing config:")
          (print-conf b-cfg))
        (cljs.env/ensure
         (build-cljs "figwheel-main-option-build"
                     source
                     (:options b-cfg) cljs.env/*compiler*)
          (cljs.cli/default-main repl-env-fn b-cfg))))))

(defn default-compile [repl-env-fn cfg]
  (let [{:keys [options repl-options repl-env-options ::config] :as b-cfg} (update-config cfg)
        {:keys [mode pprint-config]} config
        repl-env (apply repl-env-fn (mapcat identity repl-env-options))
        cenv (cljs.env/default-compiler-env options)]
    (validate-fix-target-classpath! b-cfg)
    (binding [*base-config* cfg
              *config* b-cfg]
      (cljs.env/with-compiler-env cenv
        (log/trace "Verbose config:" (with-out-str (pprint b-cfg)))
        (if pprint-config
          (do
            (log/info ":pprint-config true - printing config:")
            (print-conf b-cfg))
          (binding [cljs.repl/*repl-env* repl-env
                    figwheel.core/*config* (select-keys config [:hot-reload-cljs :broadcast-reload])]
            (let [fw-mode? (figwheel-mode? b-cfg)]
              (build config options cenv)
              (when-not (= mode :build-once)
                (start-background-builds (assoc cfg
                                                ::start-figwheel-options
                                                config))
                (doseq [init-fn (::initializers b-cfg)] (init-fn))
                (log/trace "Figwheel.core config:" (pr-str figwheel.core/*config*))
                (figwheel.core/start*)
                (cond
                  (= mode :repl)
                  ;; this forwards command line args
                  (repl repl-env repl-options)
                  (= mode :serve)
                  ;; we need to get the server host:port args
                  (serve {:repl-env repl-env
                          :repl-options repl-options
                          :join? (get b-cfg ::join-server? true)}))))))))))

(defn start-build-arg->build-options [build]
  (let [[build-id build-options config]
        (if (map? build)
          [(:id build) (:options build)
           (:config build)]
          [build])
        build-id (name build-id)
        options  (or (and (not build-options)
                          (get-build build-id))
                     build-options
                     {})
        config  (or config (meta options))]
    (cond-> {:id build-id
             :options options}
      config (assoc :config config))))

(defn start*
  ([join-server? build] (start* nil nil build))
  ([join-server? figwheel-options build & background-builds]
   (assert build "Figwheel Start: build argument required")
   (let [{:keys [id] :as build} (start-build-arg->build-options build)
         cfg
         (cond-> {:options (:options build)
                  ::join-server? (if (true? join-server?) true false)}
           figwheel-options (assoc ::start-figwheel-options figwheel-options)
           id    (assoc ::build (dissoc build :options))
           (not (get figwheel-options :mode))
           (assoc-in [::config :mode] :repl)
           (not-empty background-builds)
           (assoc ::background-builds (mapv
                                       start-build-arg->build-options
                                       background-builds)))]
     cfg
     (default-compile cljs.repl.figwheel/repl-env cfg))))

(defn start
  "Starts Figwheel.

  Example:

  (start \"dev\") ;; will look up the configuration from figwheel-main.edn
                  ;; and dev.cljs.edn

  With inline build config:
  (start {:id \"dev\" :options {:main 'example.core}})

  With inline figwheel config:
  (start {:css-dirs [\"resources/public/css\"]} \"dev\")

  With inline figwheel and build config:
  (start {:css-dirs [\"resources/public/css\"]}
         {:id \"dev\" :options {:main 'example.core}})

  If you don't want to launch a REPL:
  (start {:css-dirs [\"resources/public/css\"]
          :mode :serve}
         {:id \"dev\" :options {:main 'example.core}})"
  [& args]
  (apply start* false args))

(defn start-join
  "Starts figwheel and blocks, useful when starting figwheel as a
  server only i.e. `:mode :serve`  from a script."
  [& args]
  (apply start* true args))

;; ----------------------------------------------------------------------------
;; REPL api
;; ----------------------------------------------------------------------------

(defn currently-watched-ids []
  (set (map second (filter
               #(and (coll? %) (= (first %) ::autobuild))
               (keys (:watches @fww/*watcher*))))))

(defn currently-available-ids []
  (into (currently-watched-ids)
        (map second (keep #(when (fww/real-file? %)
                             (re-matches #"(.+)\.cljs\.edn" (.getName %)))
                          (file-seq (io/file "."))))))

(defn config-for-id [id]
  (update-config (build-opt *base-config* "dev")))

(defn clean-build [{:keys [output-to output-dir]}]
  (when (and output-to output-dir)
    (doseq [file (cons (io/file output-to)
                       (reverse (file-seq (io/file output-dir))))]
      (when (.exists file) (.delete file)))))

(defn select-autobuild-watches [ids]
  (->> ids
       (map #(vector ::autobuild %))
       (select-keys (:watches @fww/*watcher*))
       vals))

(defn warn-on-bad-id [ids]
  (when-let [bad-ids (not-empty (remove (currently-watched-ids) ids))]
    (doseq [bad-id bad-ids]
      (println "No autobuild currently has id:" bad-id))))

;; TODO this should clean ids that are not currently running as well
;; TODO should this default to cleaning all builds??
;; I think yes
(defn clean* [ids]
  (let [ids (->> ids (map name) distinct)]
    (warn-on-bad-id ids)
    (doseq [watch' (select-autobuild-watches ids)]
      (when-let [options (-> watch' ::watch-info :options)]
        (println "Cleaning build id:" (-> watch' ::watch-info :id))
        (clean-build options)))))

(defmacro clean [& ids]
  (clean* (map name ids))
  nil)

(defn status* []
  (println "------- Figwheel Main Status -------")
  (if-let [ids (not-empty (currently-watched-ids))]
    (println "Currently building:" (string/join ", " ids))
    (println "No builds are currently being built.")))

(defmacro status []
  (status*) nil)

(defn stop-builds* [ids]
  (let [ids (->> ids (map name) distinct)]
    (warn-on-bad-id ids)
    (doseq [k (map #(vector ::autobuild %) ids)]
      (when (-> fww/*watcher* deref :watches (get k))
        (println "Stopped building id:" (last k))
        (fww/remove-watch! k)))))

;; TODO should this default to stopping all builds??
;; I think yes
(defmacro stop-builds [& ids]
  (stop-builds* ids)
  nil)

(defn main-build? [id]
  (and *config* (= (name id) (-> *config* ::build :id))))

(defn hydrate-all-background-builds [cfg ids]
  (reduce background-build-opt (dissoc cfg ::background-builds) ids))

(defn start-builds* [ids]
  (let [ids (->> ids (map name) distinct)
        already-building (not-empty (filter (currently-watched-ids) ids))
        ids (filter (complement (currently-watched-ids)) ids)]
    (when (not-empty already-building)
      (doseq [i already-building]
        (println "Already building id: " i)))
    (let [main-build-id     (first (filter main-build? ids))
          bg-builds (remove main-build? ids)]
      (when main-build-id
        (let [{:keys [options repl-env-options ::config]} *config*
              {:keys [watch-dirs]} config]
          (println "Starting build id:" main-build-id)
          (bapi/build (apply bapi/inputs watch-dirs) options cljs.env/*compiler*)
          (watch-build main-build-id
                       watch-dirs options
                       cljs.env/*compiler*
                       (select-keys config [:reload-clj-files :wait-time-ms]))
          (when (first (filter #{'figwheel.core} (:preloads options)))
            (binding [cljs.repl/*repl-env* (figwheel.repl/repl-env*
                                            (select-keys repl-env-options
                                                         [:connection-filter]))]
              (figwheel.core/start*)))))
      (when (not-empty bg-builds)
        (let [cfg (hydrate-all-background-builds
                   {::start-figwheel-options (::config *config*)}
                   bg-builds)]
          (start-background-builds cfg))))))

;; TODO should this default to stopping all builds??
;; I think yes
(defmacro start-builds [& ids]
  (start-builds* ids)
  nil)

(defn reload-config* []
  (println "Reloading config!")
  (set! *config* (update-config *base-config*)))

(defn reset* [ids]
  (let [ids (->> ids (map name) distinct)
        ids (or (not-empty ids) (currently-watched-ids))]
    (clean* ids)
    (stop-builds* ids)
    (reload-config*)
    (start-builds* ids)
    nil))

(defmacro reset [& ids]
  (reset* ids))

(defn build-once* [ids]
  (let [ids (->> ids (map name) distinct)
        bad-ids (filter (complement (currently-available-ids)) ids)
        good-ids (filter (currently-available-ids) ids)]
    (when (not-empty bad-ids)
      (doseq [i bad-ids]
        (println "Build id not found:" i)))
    (when (not-empty good-ids)
      ;; clean?
      (doseq [i good-ids]
        (let [{:keys [options ::config]} (config-for-id i)
              input (if-let [paths (not-empty (:watch-dirs config))]
                      (apply bapi/inputs paths)
                      (when-let [source (when (:main options)
                                          (:uri (bapi/ns->location (symbol (:main options)))))]
                        source))]
          (when input
            (build-cljs i input options
                         (cljs.env/default-compiler-env options))))))))

(defmacro build-once [& ids]
  (build-once* ids)
  nil)

;; ----------------------------------------------------------------------------
;; Main
;; ----------------------------------------------------------------------------

(defn fix-simple-bool-arg* [flags args]
  (let [[pre post] (split-with (complement flags) args)]
    (if (empty? post)
      pre
      (concat pre [(first post) "true"] (rest post)))))

(defn fix-simple-bool-args [flags args]
  (reverse
   (reduce (fn [accum arg]
             (if (and (flags (first accum))
                      (not (#{"true" "false"} arg)))
               (-> accum
                   (conj "true")
                   (conj arg))
               (conj accum arg)))
           (list)
           args)))

(defn -main [& args]
  (alter-var-root #'cli/default-commands cli/add-commands figwheel-commands)
  (try
    (let [args       (fix-simple-bool-args #{"-pc" "--pprint-config"} args)
          [pre post] (split-with (complement #{"-re" "--repl-env"}) args)
          _          (when (not-empty post)
                       (throw
                        (ex-info (str "figwheel.main does not support the --repl-env option\n"
                                      "The figwheel REPL is implicitly used.\n"
                                      "Perhaps you were intending to use the --target option?")
                                 {::error true})))
          args'      (if (empty? post) (concat ["-re" "figwheel"] args) args)
          args'      (if (empty? args) (concat args' ["-r"]) args')]
      (with-redefs [cljs.cli/default-compile default-compile
                    cljs.cli/load-edn-opts load-edn-opts]
        (apply cljs.main/-main args')))
    (catch Throwable e
      (let [d (ex-data e)]
        (if (or (:figwheel.main.schema/error d)
                (:cljs.main/error d)
                (::error d))
          (println (.getMessage e))
          (throw e))))))


))

#_(def test-args
  (concat ["-co" "{:aot-cache false :asset-path \"out\"}" "-b" "dev" "-e" "(figwheel.core/start-from-repl)"]
          (string/split "-w src -d target/public/out -o target/public/out/mainer.js -c exproj.core -r" #"\s")))

#_(handle-build-opt (concat (first (split-at-main-opt args)) ["-h"]))

#_(apply -main args)
#_(.stop @server)
