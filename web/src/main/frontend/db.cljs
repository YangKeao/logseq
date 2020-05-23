(ns frontend.db
  (:require [datascript.core :as d]
            [frontend.util :as util]
            [medley.core :as medley]
            [datascript.transit :as dt]
            [frontend.format :as format]
            [frontend.format.mldoc :as mldoc]
            [frontend.format.block :as block]
            [frontend.state :as state]
            [clojure.string :as string]
            [clojure.set :as set]
            [frontend.utf8 :as utf8]
            [cljs-bean.core :as bean]
            [frontend.config :as config]
            [rum.core :as rum]
            [goog.object :as gobj]
            ["localforage" :as localforage]
            [promesa.core :as p]))

;; offline db
(def store-name "dbs")
(.config localforage
         (bean/->js
          {:name "logseq-datascript"
           :version 1.0
           :storeName store-name}))

(defonce localforage-instance (.createInstance localforage store-name))

(defn get-repo-path
  [url]
  (->> (take-last 2 (string/split url #"/"))
       (string/join "/")))

(defn datascript-db
  [repo]
  (str "logseq-db/" (get-repo-path repo)))

(defn clear-store!
  []
  (p/let [_ (.clear localforage)
          dbs (js/window.indexedDB.databases)]
    (doseq [db dbs]
      (js/window.indexedDB.deleteDatabase (gobj/get db "name")))))

(defn remove-db!
  [repo]
  (.removeItem localforage-instance (datascript-db repo)))

(def react util/react)

(defn get-repo-name
  [url]
  (last (string/split url #"/")))

(defonce conns
  (atom {}))

(defn get-conn
  ([]
   (get-conn (state/get-current-repo) true))
  ([repo-or-deref?]
   (if (boolean? repo-or-deref?)
     (get-conn (state/get-current-repo) repo-or-deref?)
     (get-conn repo-or-deref? true)))
  ([repo deref?]
   (let [repo (if repo repo (state/get-current-repo))]
     (when-let [conn (get @conns (datascript-db repo))]
       (if deref?
         @conn
         conn)))))

(defn remove-conn!
  [repo]
  (swap! conns dissoc (datascript-db repo)))

;; A page can corresponds to multiple files (same title),
;; a month journal file can have multiple pages,
;; also, each heading can be treated as a page if we support
;; "zoom edit".
(def schema
  {:db/ident        {:db/unique :db.unique/identity}

   ;; user
   :me/name  {}
   :me/email {}
   :me/avatar {}

   ;; repo
   :repo/url        {:db/unique :db.unique/identity}
   :repo/cloned?    {}
   :git/latest-commit {}
   :git/status {}
   ;; last error, better we should record all the errors
   :git/error {}

   ;; file
   :file/path       {:db/unique :db.unique/identity
                     :db/index       true}
   ;; TODO: calculate memory/disk usage
   ;; :file/size       {}

   :page/name       {:db/unique      :db.unique/identity
                     :db/index       true}
   :page/file       {:db/valueType   :db.type/ref
                     :db/index       true}
   :page/directives {}
   :page/alias      {:db/valueType   :db.type/ref
                     :db/cardinality :db.cardinality/many
                     :db/index       true}
   :page/tags       {:db/valueType   :db.type/ref
                     :db/cardinality :db.cardinality/many
                     :db/index       true}
   :page/created-at {}
   :page/last-modified-at {}
   :page/journal?   {}
   :page/journal-day {}

   ;; heading
   :heading/uuid   {:db/unique      :db.unique/identity
                    :db/index       true}
   :heading/file   {:db/valueType   :db.type/ref
                    :db/index       true}
   :heading/format {}
   ;; belongs to which page
   :heading/page   {:db/valueType   :db.type/ref
                    :db/index       true}
   ;; referenced pages
   :heading/ref-pages {:db/valueType   :db.type/ref
                       :db/cardinality :db.cardinality/many}
   ;; referenced headings
   :heading/ref-headings {:db/valueType   :db.type/ref
                          :db/cardinality :db.cardinality/many}
   :heading/content {}
   :heading/anchor {}
   :heading/marker {}
   :heading/priority {}
   :heading/level {}
   :heading/tags {:db/valueType   :db.type/ref
                  :db/cardinality :db.cardinality/many
                  :db/index       true}
   :heading/meta {}
   :heading/properties {}

   :heading/created-at {}
   :heading/last-modified-at {}
   :heading/parent {:db/valueType   :db.type/ref}

   ;; tag
   :tag/name       {:db/unique :db.unique/identity
                    :db/index       true}})

;; transit serialization

(defn db->string [db]
  (dt/write-transit-str db))

(defn string->db [s]
  (dt/read-transit-str s))

;; persisting DB between page reloads
(defn persist [repo db]
  (.setItem localforage-instance
            (datascript-db repo)
            (db->string db)))

(defn reset-conn! [conn db]
  (reset! conn db))

;; Query atom of map of Key ([repo q inputs]) -> atom
(defonce query-state (atom {}))
(defn add-q!
  [k query inputs result]
  (let [result-atom (atom result)]
    (swap! query-state assoc k {:query query
                                :inputs inputs
                                :result result-atom})
    result-atom))

(defn set-new-result!
  [k new-result]
  (util/d "set new result spend: "
          (fn []
            (when-let [result-atom (get-in @query-state [k :result])]
              (reset! result-atom new-result)))))

;; TODO: support multiple keys, e.g., a heading change might affects the page, right sidebar or even agenda
(defn get-handler-key
  [{:keys [key data]}]
  (cond
    (coll? key)
    key

    :else
    (case key
      :page/headings
      (let [{:heading/keys [page]} data]
        (when-let [page-id (:db/id page)]
          [:page/headings page-id]))
      [key])))

(defn q
  [repo k query & inputs]
  (when-let [conn (get-conn repo)]
    (let [result (if (seq inputs)
                   (apply d/q query conn inputs)
                   (d/q query conn))]
      (add-q! (vec (cons repo k)) query inputs result))))

(defn refresh-query-result!
  [repo query inputs]
  (let [k [repo query inputs]]
    (when-let [conn (get-conn repo)]
      (let [new-result (apply d/q query conn inputs)]
        (set-new-result! k new-result)))))

(defn transact!
  ([tx-data]
   (transact! (state/get-current-repo) tx-data))
  ([repo-url tx-data]
   (let [tx-data (remove nil? tx-data)]
     (when (seq tx-data)
       (when-let [conn (get-conn repo-url)]
         (d/transact! conn (vec tx-data)))))))

(defn transact-react!
  [repo-url tx-data {:keys [key data] :as handler-opts}]
  (let [repo-url (or repo-url (state/get-current-repo))
        tx-data (remove nil? tx-data)]
    (when (seq tx-data)
      (when-let [conn (get-conn repo-url false)]
        (let [db (:db-after (d/transact! conn (vec tx-data)))
              handler-key (vec (cons repo-url (get-handler-key handler-opts)))
              {:keys [query inputs]} (get @query-state handler-key)
              new-result (apply d/q query db inputs)]
          (set-new-result! handler-key new-result))))))

(defn pull
  ([eid]
   (pull '[*] eid))
  ([selector eid]
   (when-let [conn (get-conn)]
     (d/pull conn
             selector
             eid))))

(defn pull-many
  ([eids]
   (pull-many '[*] eids))
  ([selector eids]
   (when-let [conn (get-conn)]
     (d/pull-many conn selector eids))))

(defn pull-heading
  [id]
  (when-let [conn (get-conn (state/get-current-repo))]
    (d/q '[:find (pull ?heading '[*])
           :in $ ?id
           :where
           [?heading :heading/uuid ?id]]
      conn
      id)))

(defn entity
  [id-or-lookup-ref]
  (when-let [db (get-conn (state/get-current-repo) true)]
    (d/entity db id-or-lookup-ref)))

(defn kv
  [key value]
  {:db/id -1
   :db/ident key
   key value})

;; queries

(defn- distinct-result
  [query-result]
  (-> query-result
      seq
      flatten
      distinct))

(def seq-flatten (comp flatten seq))

(defn get-all-tags
  [repo]
  (distinct-result
   (d/q '[:find ?tags
          :where
          [?h :heading/tags ?tags]]
     (get-conn repo))))

(defn- remove-journal-files
  [files]
  (remove
   (fn [file]
     (string/starts-with? file "journals/"))
   files))

(defn get-pages
  [repo]
  (->> (q repo [:pages]
         '[:find ?page-name
           :where
           [?page :page/name ?page-name]])
       (react)
       (map first)
       distinct))

(defn get-page-alias
  [repo page-name]
  (when-let [conn (and repo (get-conn repo))]
    (some->> (d/q '[:find ?alias-name
                    :in $ ?page-name
                    :where
                    [?page :page/name ?page-name]
                    [?page :page/alias ?alias]
                    [?alias :page/name ?alias-name]]
               conn
               page-name)
             seq-flatten
             distinct
             remove-journal-files)))

(defn d-get-page-alias
  [repo page-name]
  (when-let [conn (and repo (get-conn repo))]
    (some->> (d/q '[:find ?alias-name
                    :in $ ?page-name
                    :where
                    [?page :page/name ?page-name]
                    [?page :page/alias ?alias]
                    [?alias :page/name ?alias-name]]
               conn
               page-name)
             seq-flatten
             distinct
             remove-journal-files)))

(defn get-files
  [repo]
  (->> (q repo [:files]
         '[:find ?file-path
           :where
           [?file :file/path ?file-path]])
       (react)
       (map first)
       (distinct)
       (sort)))

(defn get-files-headings
  [repo-url paths]
  (let [paths (set paths)
        pred (fn [db e]
               (contains? paths e))]
    (-> (d/q '[:find ?heading
               :in $ ?pred
               :where
               [?file :file/path ?path]
               [(?pred $ ?path)]
               [?heading :heading/file ?file]]
          (get-conn repo-url) pred)
        seq-flatten)))

(defn delete-headings
  [repo-url files]
  (when (seq files)
    (let [headings (get-files-headings repo-url files)]
      (mapv (fn [eid] [:db.fn/retractEntity eid]) headings))))

(defn delete-files
  [files]
  (mapv (fn [path] [:db.fn/retractEntity [:file/path path]]) files))

(defn get-file-headings
  [repo-url path]
  (-> (d/q '[:find ?heading
             :in $ ?path
             :where
             [?file :file/path ?path]
             [?heading :heading/file ?file]]
        (get-conn repo-url) path)
      seq-flatten))

(defn sort-by-pos
  [headings]
  (sort-by (fn [heading]
             (get-in heading [:heading/meta :pos]))
           headings))

(defn get-file-after-headings
  [repo-url file-id end-pos]
  (when end-pos
    (let [pred (fn [db meta]
                 (>= (:pos meta) end-pos))]
      (-> (d/q '[:find (pull ?heading [*])
                 :in $ ?file-id ?pred
                 :where
                 [?heading :heading/file ?file-id]
                 [?heading :heading/meta ?meta]
                 [(?pred $ ?meta)]]
            (get-conn repo-url) file-id pred)
          seq-flatten
          sort-by-pos))))

(defn delete-file-headings!
  [repo-url path]
  (let [headings (get-file-headings repo-url path)]
    (mapv (fn [eid] [:db.fn/retractEntity eid]) headings)))

(defn reset-contents-and-headings!
  [repo-url contents headings-pages delete-files delete-headings]
  (let [files (doall
               (map (fn [[file content]]
                      (state/set-file-content! repo-url file content)
                      {:file/path file})
                 contents))
        all-data (-> (concat delete-files delete-headings files headings-pages)
                     (util/remove-nils))]
    (transact! repo-url all-data)))

(defn get-headings-by-tag
  [repo tag]
  (let [pred (fn [db tags]
               (some #(= tag %) tags))]
    (d/q '[:find (flatten (pull ?h [*]))
           :in $ ?pred
           :where
           [?h :heading/tags ?tags]
           [(?pred $ ?tags)]]
      (get-conn repo) pred)))

(defn get-heading-by-uuid
  [uuid]
  (entity [:heading/uuid uuid]))

(defn remove-key
  [repo-url key]
  (transact! repo-url [[:db.fn/retractEntity [:db/ident key]]])
  (set-new-result! [repo-url :kv key] nil))

(defn set-key-value
  [repo-url key value]
  (if value
    (transact-react! repo-url [(kv key value)]
                     {:key [:kv key]})
    (remove-key repo-url key)))

(defn get-key-value
  ([key]
   (get-key-value (state/get-current-repo) key))
  ([repo-url key]
   (when-let [db (get-conn repo-url)]
     (some-> (d/entity db key)
             key))))

(defn sub-key-value
  ([key]
   (sub-key-value (state/get-current-repo) key))
  ([repo-url key]
   (-> (q repo-url [:kv key]
         '[:find ?value
           :in $ ?key
           :where
           [?e :db/ident ?key]
           [?e ?key ?value]]
         key)
       react
       ffirst)))

(defn get-page-format
  [page-name]
  (when-let [file (:page/file (entity [:page/name page-name]))]
    (when-let [path (:file/path (entity (:db/id file)))]
      (format/get-format path))))

(defn page-alias-set
  [repo-url page]
  (let [aliases (d-get-page-alias repo-url page)]
    (set (conj aliases page))))

(defn get-page-headings
  ([page]
   (get-page-headings (state/get-current-repo)
                      page))
  ([repo-url page]
   (let [pages (page-alias-set repo-url page)
         page-id (:db/id (entity [:page/name page]))]
     (->> (q repo-url [:page/headings page-id]
            '[:find (pull ?heading [*])
              :in $ ?page
              :where
              [?p :page/name ?page]
              [?heading :heading/page ?p]
              ;; [(contains? ?pages ?page)]
              ]
            page)
          react
          seq-flatten
          sort-by-pos))))

(defn get-page-name
  [file ast]
  ;; headline
  (let [first-heading (first (filter block/heading-block? ast))
        other-name (cond
                     (and (= "Directives" (ffirst ast))
                          (not (string/blank? (:title (last (first ast))))))
                     (:title (last (first ast)))

                     first-heading
                     ;; FIXME:
                     (str (last (first (:title (second first-heading)))))

                     :else
                     nil)]
    (string/lower-case (or other-name file))))

(defn valid-journal-title?
  [title]
  (and title
       (not (js/isNaN (js/Date.parse title)))))

(defn get-heading-content
  [utf8-content heading]
  (let [meta (:meta heading)]
    (if-let [end-pos (:end-pos meta)]
      (utf8/substring utf8-content
                      (:pos meta)
                      end-pos)
      (utf8/substring utf8-content
                      (:pos meta)))))

;; file

(defn journal-page-name->int
  [page-name]
  (let [[m d y] (-> (last (string/split page-name #", "))
                    (string/split #"/"))]
    (util/parse-int (str y m d))))

(defn extract-pages-and-headings
  [format ast directives file content utf8-content journal? pages-fn]
  (println "Parsing file: " file)
  (try
    (let [headings (block/extract-headings ast (utf8/length utf8-content))
          pages (pages-fn headings ast)
          ref-pages (atom #{})
          headings (mapcat
                    (fn [[page headings]]
                      (if page
                        (map (fn [heading]
                               (let [heading-ref-pages (seq (:ref-pages heading))]
                                 (when heading-ref-pages
                                   (swap! ref-pages set/union (set heading-ref-pages)))
                                 (-> heading
                                     (dissoc :ref-pages)
                                     (assoc :heading/content (get-heading-content utf8-content heading)
                                            :heading/file [:file/path file]
                                            :heading/format format
                                            :heading/page [:page/name (string/lower-case page)]
                                            :heading/ref-pages (mapv
                                                                (fn [page]
                                                                  {:page/name (string/lower-case page)})
                                                                heading-ref-pages)))))
                          headings)))
                    pages)
          headings (block/safe-headings headings)
          pages (map
                  (fn [page]
                    (let [page-file? (= page (string/lower-case file))
                          other-alias (and (:alias directives)
                                           (seq (remove #(= page %)
                                                        (:alias directives))))
                          other-alias (distinct
                                       (if page-file?
                                         other-alias
                                         (conj other-alias (string/lower-case file))))]
                      (cond->
                          {:page/name page
                           :page/file [:file/path file]
                           :page/journal? journal?
                           :page/journal-day (if journal?
                                               (journal-page-name->int page)
                                               0)}
                        (seq directives)
                        (assoc :page/directives directives)

                        other-alias
                        (assoc :page/alias
                               (map
                                 (fn [alias]
                                   (let [alias (string/lower-case alias)]
                                     {:page/name alias
                                      :page/alias (map
                                                    (fn [alias]
                                                      {:page/name alias})
                                                    (conj
                                                     (remove #{alias} other-alias)
                                                     page))}))
                                 other-alias))

                        (:tags directives)
                        (assoc :page/tags
                               (map
                                 (fn [tag]
                                   {:tag/name (string/lower-case tag)})
                                 (:tags directives)))))
                    )
                  (map first pages))
          pages (concat
                 pages
                 (map
                   (fn [page]
                     {:page/name (string/lower-case page)})
                   @ref-pages))]
      (vec
       (->> (concat
             pages
             headings)
            (remove nil?))))
    (catch js/Error e
      (prn "Parsing error: " e))))

;; check journal formats and report errors
(defn extract-headings-pages
  [file content utf8-content]
  (if (string/blank? content)
    []
    (let [journal? (string/starts-with? file "journals/")
          format (format/get-format file)
          ast (mldoc/->edn content
                           (mldoc/default-config format))
          directives (let [directives (and (seq ast)
                                           (= "Directives" (ffirst ast))
                                           (last (first ast)))]
                       (if (and directives (seq directives))
                         directives))]
      (if journal?
        (extract-pages-and-headings
         format ast directives
         file content utf8-content true
         (fn [headings _ast]
           (loop [pages {}
                  last-page-name nil
                  headings headings]
             (if (seq headings)
               (let [[{:keys [level title] :as heading} & tl] headings]
                 (if (and (= level 1)
                          (when-let [title (last (first title))]
                            (valid-journal-title? title)))
                   (let [page-name (let [title (last (first title))]
                                     (and title (string/lower-case title)))
                         new-pages (assoc pages page-name [heading])]
                     (recur new-pages page-name tl))
                   (let [new-pages (update pages last-page-name (fn [headings]
                                                                  (vec (conj headings heading))))]
                     (recur new-pages last-page-name tl))))
               pages))))
        (extract-pages-and-headings
         format ast directives
         file content utf8-content false
         (fn [headings ast]
           [[(get-page-name file ast) headings]]))))))

(defn extract-all-headings-pages
  [contents]
  (vec
   (mapcat
    (fn [[file content] contents]
      (when content
        (let [utf8-content (utf8/encode content)]
          (extract-headings-pages file content utf8-content))))
    contents)))

;; TODO: compare headings
(defn reset-file!
  [repo-url file content]
  (state/set-file-content! repo-url file content)
  (let [format (format/get-format file)
        utf8-content (utf8/encode content)
        file-content [{:file/path file}]
        tx (if (contains? config/hiccup-support-formats format)
             (let [delete-headings (delete-file-headings! repo-url file)
                   headings-pages (extract-headings-pages file content utf8-content)]
               (concat file-content delete-headings headings-pages))
             file-content)]
    (transact! repo-url tx)))

;; marker should be one of: TODO, DOING, IN-PROGRESS
;; time duration
;; TODO: posh doesn't support or query
(defn get-agenda
  ([repo]
   (get-agenda (state/get-current-repo) :week))
  ([repo time]
   ;; TODO:
   (let [duration (case time
                    :today []
                    :week  []
                    :month [])
         pred (fn [db marker]
                (contains? #{"TODO" "DOING" "IN-PROGRESS"} marker))]
     (->>
      (q repo [:agenda]
        '[:find (pull ?h [*])
          :in $ ?pred
          :where
          [?h :heading/marker ?marker]
          [(?pred $ ?marker)]]
        pred)
      react
      seq-flatten))))

(defn get-current-journal-path
  []
  (let [{:keys [year month]} (util/get-date)]
    (util/journals-path year month (state/get-preferred-format))))

(defn get-journal
  ([]
   (get-journal (util/journal-name)))
  ([page-name]
   [page-name (get-page-headings page-name)]))

(defn- date->int
  [date]
  (util/parse-int
   (string/replace (util/ymd date) "/" "")))

(defn get-journals-length
  []
  (let [today (date->int (js/Date.))]
    (d/q '[:find (count ?page) .
           :in $ ?today
           :where
           [?page :page/journal? true]
           [?page :page/journal-day ?journal-day]
           [(<= ?journal-day ?today)]]
      (get-conn (state/get-current-repo))
      today)))

;; cache this
(defn get-latest-journals
  ([n]
   (get-latest-journals (state/get-current-repo) n))
  ([repo-url n]
   (let [date (js/Date.)
         _ (.setDate date (- (.getDate date) (dec n)))
         before-day (date->int date)
         today (date->int (js/Date.))
         pages (->>
                (q repo-url [:journals]
                  '[:find ?page-name ?journal-day
                    :in $ ?before-day ?today
                    :where
                    [?page :page/name ?page-name]
                    [?page :page/journal? true]
                    [?page :page/journal-day ?journal-day]
                    [(<= ?before-day ?journal-day ?today)]]
                  before-day
                  today)
                (react)
                (sort-by last)
                (reverse)
                (map first))]
     (mapv
      (fn [page]
        [page
         (get-page-headings repo-url page)
         (get-page-format page)])
      pages))))

(defn me-tx
  [db {:keys [name email avatar repos]}]
  (util/remove-nils {:me/name name
                     :me/email email
                     :me/avatar avatar}))

(defn with-dummy-heading
  ([headings format]
   (with-dummy-heading headings format {} false))
  ([headings format default-option journal?]
   (cond
     (or (and (not journal?) (seq headings))
         (and journal? (> (count headings) 1)))
     headings

     :else
     (let [last-heading (last headings)
           end-pos (get-in last-heading [:heading/meta :end-pos] 0)
           dummy (merge last-heading
                        (let [uuid (d/squuid)]
                          {:heading/uuid uuid
                           :heading/title ""
                           :heading/content (config/default-empty-heading format)
                           :heading/format format
                           :heading/level 2
                           :heading/priority nil
                           :heading/anchor (str uuid)
                           :heading/meta {:pos end-pos
                                          :end-pos nil}
                           :heading/children nil
                           :heading/dummy? true
                           :heading/marker nil
                           :heading/lock? false})
                        default-option)]
       (vec (concat headings [dummy]))))))

;; get pages that this page referenced
(defn get-page-referenced-pages
  [repo page]
  (let [pages (page-alias-set repo page)
        page-id (:db/id (entity {:page/name page}))
        ref-pages (->> (q repo [:page/ref-pages page-id]
                         '[:find ?ref-page-name
                           :in $ ?pages
                           :where
                           [?p :page/name ?page]
                           [?heading :heading/page ?p]
                           [?heading :heading/ref-pages ?ref-page]
                           [?ref-page :page/name ?ref-page-name]
                           [(contains? ?pages ?page)]]
                         pages)
                       react
                       seq-flatten)]
    (mapv (fn [page] [page (get-page-alias repo page)]) ref-pages)))

;; get pages who mentioned this page
(defn get-pages-that-mentioned-page
  [repo page]
  (let [page-id (:db/id (entity {:page/name page}))
        pages (page-alias-set repo page)
        mentioned-pages (->> (q repo [:page/mentioned-pages page-id]
                               '[:find ?mentioned-page-name
                                 :in $ ?pages ?page-name
                                 :where
                                 [?heading :heading/ref-pages ?p]
                                 [?p :page/name ?page]
                                 [(contains? ?pages ?page)]
                                 [?heading :heading/page ?mentioned-page]
                                 [?mentioned-page :page/name ?mentioned-page-name]]
                               pages
                               page)
                             react
                             seq-flatten)]
    (mapv (fn [page] [page (get-page-alias repo page)]) mentioned-pages)))

;; TODO: sorted by last-modified-at
(defn get-page-referenced-headings
  [page]
  (when-let [repo (state/get-current-repo)]
    (let [page-id (:db/id (entity {:page/name page}))
          pages (page-alias-set repo page)]
      (->> (q repo [:page/refed-headings page-id]
             '[:find (pull ?heading [*])
               :in $ ?pages
               :where
               [?ref-page :page/name ?page]
               [?heading :heading/ref-pages ?ref-page]
               [(contains? ?pages ?page)]]
             pages)
           react
           seq-flatten))))

(defn get-heading-referenced-headings
  [heading-uuid]
  (when-let [repo (state/get-current-repo)]
    (->> (q repo [:heading/refed-headings heading-uuid]
           '[:find (pull ?ref-heading [*])
             :in $ ?page-name
             :where
             [?heading :heading/uuid ?heading-uuid]
             [?heading :heading/ref-headings ?ref-heading]]
           heading-uuid)
         react
         seq-flatten)))

(defn get-matched-headings
  [pattern limit]
  (when-let [repo (state/get-current-repo)]
    (->> (q repo [:matched-headings]
           '[:find ?heading
             :in $ ?pattern
             :where
             [?heading :heading/content ?content]
             [(re-find ?pattern ?content)]]
           pattern)
         react
         (take limit)
         seq-flatten
         (pull-many '[:heading/uuid
                      :heading/content
                      {:heading/page [:page/name]}]))))

;; TODO: Does the result preserves the order of the arguments?
(defn get-headings-contents
  [heading-uuids]
  (let [db (get-conn (state/get-current-repo))]
    (d/pull-many db '[:heading/content]
                 (mapv (fn [id] [:heading/uuid id]) heading-uuids))))

(defn journal-page?
  [page-name]
  (:page/journal? (entity [:page/name page-name])))

(defn mark-repo-as-cloned
  [repo-url]
  (transact!
    [{:repo/url repo-url
      :repo/cloned? true}]))

(defn cloned?
  [repo-url]
  (->
   (d/q '[:find ?cloned
          :in $ ?repo-url
          :where
          [?repo :repo/url ?repo-url]
          [?repo :repo/cloned? ?cloned]]
     (get-conn repo-url) repo-url)
   ffirst))

(defn reset-config!
  [repo-url content]
  (let [config (some->> content
                        (js/JSON.parse)
                        (bean/->clj))]
    (state/set-config! repo-url config)
    config))

(defn start-db-conn!
  [me repo listen-handler]
  (let [db-name (datascript-db repo)
        db-conn (d/create-conn schema)]
    (swap! conns assoc db-name db-conn)
    (listen-handler repo db-conn)
    (d/transact! db-conn [(me-tx (d/db db-conn) me)])))

(defn restore! [{:keys [repos] :as me} listen-handler render-fn]
  (-> (p/all
       (for [{:keys [id url]} repos]
         (let [repo url
               db-name (datascript-db repo)
               db-conn (d/create-conn schema)]
           (swap! conns assoc db-name db-conn)
           (p/let [stored (.getItem localforage-instance db-name)]
             (if stored
               (let [stored-db (string->db stored)
                     attached-db (d/db-with stored-db [(me-tx stored-db me)])]
                 (when (= (:schema stored-db) schema) ;; check for code update
                   (reset-conn! db-conn attached-db)))
               (d/transact! db-conn [(me-tx (d/db db-conn) me)]))
             (listen-handler repo db-conn)
             (when-let [config-content (state/get-file url config/config-file)]
               (reset-config! url config-content))))))
      (p/then render-fn)))

(defn build-page-graph
  [page theme]
  (let [dark? (= "dark" theme)]
    (when-let [repo (state/get-current-repo)]
      (let [page (string/lower-case page)
            ref-pages (get-page-referenced-pages repo page)
            mentioned-pages (get-pages-that-mentioned-page repo page)
            edges (concat
                   (map (fn [[p aliases]]
                          [page p]) ref-pages)
                   (map (fn [[p aliases]]
                          [p page]) mentioned-pages))
            other-pages (->> (concat (map first ref-pages)
                                     (map first mentioned-pages))
                             (remove nil?)
                             (set))
            other-pages-edges (mapcat
                               (fn [page]
                                 (let [ref-pages (-> (map first (get-page-referenced-pages repo page))
                                                     (set)
                                                     (set/intersection other-pages))
                                       mentioned-pages (-> (map first (get-pages-that-mentioned-page repo page))
                                                           (set)
                                                           (set/intersection other-pages))]
                                   (concat
                                    (map (fn [p] [page p]) ref-pages)
                                    (map (fn [p] [p page]) mentioned-pages))))
                               other-pages)
            edges (->> (concat edges other-pages-edges)
                       (remove nil?)
                       (distinct)
                       (map (fn [[from to]]
                              {:from from
                               :to to})))
            get-connections (fn [page]
                              (count (filter (fn [{:keys [from to]}]
                                               (or (= from page)
                                                   (= to page)))
                                             edges)))
            nodes (->> (concat
                        [page]
                        (map first ref-pages)
                        (map first mentioned-pages))
                       (remove nil?)
                       (distinct)
                       (mapv (fn [p]
                               (cond->
                                   {:id p
                                    :label (util/capitalize-all p)
                                    :value (get-connections p)}
                                 (= p page)
                                 (assoc :color "#5850ec")
                                 dark?
                                 (assoc :font {:color "#dfdfdf"})
                                 ))))]
        {:nodes nodes
         :edges edges}))))

(comment
  (defn debug!
    []
    (let [repos (->> (get-in @state/state [:me :repos])
                     (map :url))]
      (mapv (fn [repo]
              {:repo/current (state/get-current-repo)
               :repo repo
               :git/cloned? (cloned? repo)
               :git/status (get-key-value repo :git/status)
               :git/latest-commit (get-key-value repo :git/latest-commit)
               :git/error (get-key-value repo :git/error)})
            repos)))

  (def react deref)

  (def react rum/react)
  )
