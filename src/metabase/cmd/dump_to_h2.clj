(ns metabase.cmd.dump-to-h2
  "Commands for dumping data to an H2 file from another database.
   Run this with `lein run dump-to-h2` or `java -jar metabase.jar dump-to-h2`.

   Test this as follows:
  ```MB_DB_TYPE=h2 MB_DB_FILE=dump.h2.db lein run dump-to-h2 \"postgres://postgres@localhost:5432/metabase\"```
   Validate with:
  ```MB_DB_TYPE=postgres MB_DB_HOST=localhost MB_DB_PORT=5432 MB_DB_USER=postgres MB_DB_DBNAME=metabase lein run load-from-h2 dump.h2.db```

   "

  (:require [clojure.java
             [io :as io]
             [jdbc :as jdbc]]
            [clojure.string :as str]
            [colorize.core :as color]
            [metabase
             [db :as mdb]
             [util :as u]]
            [metabase.db.migrations :refer [DataMigrations]]
            [metabase.models
             [activity :refer [Activity]]
             [card :refer [Card]]
             [card-favorite :refer [CardFavorite]]
             [collection :refer [Collection]]
             [collection-revision :refer [CollectionRevision]]
             [dashboard :refer [Dashboard]]
             [dashboard-card :refer [DashboardCard]]
             [dashboard-card-series :refer [DashboardCardSeries]]
             [dashboard-favorite :refer [DashboardFavorite]]
             [database :refer [Database]]
             [dependency :refer [Dependency]]
             [dimension :refer [Dimension]]
             [field :refer [Field]]
             [field-values :refer [FieldValues]]
             [metric :refer [Metric]]
             [metric-important-field :refer [MetricImportantField]]
             [permissions :refer [Permissions]]
             [permissions-group :refer [PermissionsGroup]]
             [permissions-group-membership :refer [PermissionsGroupMembership]]
             [permissions-revision :refer [PermissionsRevision]]
             [pulse :refer [Pulse]]
             [pulse-card :refer [PulseCard]]
             [pulse-channel :refer [PulseChannel]]
             [pulse-channel-recipient :refer [PulseChannelRecipient]]
             [revision :refer [Revision]]
             [segment :refer [Segment]]
             [session :refer [Session]]
             [setting :refer [Setting]]
             [table :refer [Table]]
             [user :refer [User]]
             [view-log :refer [ViewLog]]]
            [metabase.util.i18n :refer [trs]]
            [toucan.db :as db]
            [me.raynes.fs :as fs])
  (:import java.sql.SQLException))

(defn- println-ok [] (println (color/green "[OK]")))

(defn- dispatch-on-db-type [& _] (mdb/db-type))

;;; ------------------------------------------ Models to Migrate (in order) ------------------------------------------

(def ^:private entities
  "Entities in the order they should be serialized/deserialized. This is done so we make sure that we load load
  instances of entities before others that might depend on them, e.g. `Databases` before `Tables` before `Fields`."
  [Database
   User
   Setting
   Dependency
   Table
   Field
   FieldValues
   Segment
   Metric
   MetricImportantField
   Revision
   ViewLog
   Session
   Dashboard
   Card
   CardFavorite
   DashboardCard
   DashboardCardSeries
   Activity
   Pulse
   PulseCard
   PulseChannel
   PulseChannelRecipient
   PermissionsGroup
   PermissionsGroupMembership
   Permissions
   PermissionsRevision
   Collection
   CollectionRevision
   DashboardFavorite
   Dimension
   ;; migrate the list of finished DataMigrations as the very last thing (all models to copy over should be listed
   ;; above this line)
   DataMigrations])


;;; --------------------------------------------- H2 Connection Options ----------------------------------------------

(defn- add-file-prefix-if-needed [connection-string-or-filename]
  (if (str/starts-with? connection-string-or-filename "file:")
    connection-string-or-filename
    (str "file:" (.getAbsolutePath (io/file connection-string-or-filename)))))

(defn- h2-details [h2-connection-string-or-nil]
  (let [h2-filename (add-file-prefix-if-needed h2-connection-string-or-nil)]
    (mdb/jdbc-details {:type :h2, :db (str h2-filename ";IFEXISTS=TRUE")})))


;;; ------------------------------------------- Fetching & Inserting Rows --------------------------------------------

(defn- objects->colums+values
  "Given a sequence of objects/rows fetched from the H2 DB, return a the `columns` that should be used in the `INSERT`
  statement, and a sequence of rows (as sequences)."
  [objs]
  ;; 1) `:sizeX` and `:sizeY` come out of H2 as `:sizex` and `:sizey` because of automatic lowercasing; fix the names
  ;;    of these before putting into the new DB
  ;;
  ;; 2) Need to wrap the column names in quotes because Postgres automatically lowercases unquoted identifiers
  (let [source-keys (keys (first objs))
        dest-keys (for [k source-keys]
                    ((db/quote-fn) (name (case k
                                           :sizex :sizeX
                                           :sizey :sizeY
                                           k))))]
    {:cols dest-keys
     :vals (for [row objs]
             (map (comp u/jdbc-clob->str row) source-keys))}))

(def ^:private chunk-size 100)

(defn- insert-chunk! [target-db-conn table-name chunkk]
  (print (color/blue \.))
  (flush)
  (try
    (let [{:keys [cols vals]} (objects->colums+values chunkk)]
      (jdbc/insert-multi! target-db-conn table-name cols vals))
    (catch SQLException e
      (jdbc/print-sql-exception-chain e)
      (throw e))))

(defn- insert-entity! [target-db-conn {table-name :table, entity-name :name} objs]
  (print (u/format-color 'blue "Transferring %d instances of %s..." (count objs) entity-name))
  (flush)
  ;; The connection closes prematurely on occasion when we're inserting thousands of rows at once. Break into
  ;; smaller chunks so connection stays alive
  (doseq [chunk (partition-all chunk-size objs)]
    (insert-chunk! target-db-conn table-name chunk))
  (println-ok))

(defn- load-data! [target-db-conn app-db-connection-string-or-nil]
  (let [conn-map (mdb/parse-connection-string app-db-connection-string-or-nil)]
    (println "Conn of source: " conn-map app-db-connection-string-or-nil)
    (jdbc/with-db-connection [db-conn (mdb/jdbc-details conn-map)]
                             (doseq [{table-name :table, :as e} entities
                                     :let [rows (jdbc/query db-conn [(str "SELECT * FROM " (name table-name))])]
                                     :when (seq rows)]
                               (insert-entity! target-db-conn e rows)))))


(defn- get-target-db-conn [h2-filename-or-nil]
  (if h2-filename-or-nil
    (h2-details h2-filename-or-nil)
    (mdb/jdbc-details)))

;;; --------------------------------------------------- Public Fns ---------------------------------------------------

(defn ensure-db-file-exists! [h2-filename-or-nil]
  (if-not (fs/exists? h2-filename-or-nil)
    (do (println "Creating file: " h2-filename-or-nil)
        (fs/create (io/file h2-filename-or-nil)))
    (println "H2 target already exists: " h2-filename-or-nil)))

(defn dump-to-h2!
  "Transfer data from existing database specified by connection string
  to the H2 DB specified by env vars.  Intended as a tool for migrating
  from one instance to another using H2 as serialization target.

  Defaults to using `@metabase.db/db-file` as the connection string."
  [app-db-connection-string-or-nil
   h2-filename-or-nil]

  ;;TODO determine app-db-connection spec from (mdb/jdbc-details) or the like, don't require this command to take the conn str in

  (println "Dumping from " app-db-connection-string-or-nil " to H2: " h2-filename-or-nil " or H2 from env.")

  (let [db-type (if h2-filename-or-nil :h2 (mdb/db-type))]
    (println "Target db type: " db-type)

    (assert (#{:h2} db-type) (trs "Metabase can only transfer data from DB to H2 for migration.")))

  (assert app-db-connection-string-or-nil (trs "Metabase can only dump to H2 if it has the source db connection string."))

  (ensure-db-file-exists! h2-filename-or-nil)

  (mdb/setup-db!* (get-target-db-conn h2-filename-or-nil) true)

  (when (= :h2 (mdb/db-type))
    ;;TODO
    (trs "Don't need to migrate, just use the existing H2 file")
    (System/exit 0))



  (jdbc/with-db-transaction [target-db-conn (get-target-db-conn h2-filename-or-nil)]
                            (println "Conn of target: " target-db-conn)

                            (println-ok)

                            (println (u/format-color 'blue "Loading data..."))

                            (load-data! target-db-conn app-db-connection-string-or-nil)

                            (println-ok)

                            (jdbc/db-unset-rollback-only! target-db-conn))
  (println "Dump complete")
  )

;(dump-to-h2! "")