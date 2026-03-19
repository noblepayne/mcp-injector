(ns mcp-injector.storage
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]
            [mcp-injector.schemas :as schemas]))

(def ^:private data-dir
  (or (System/getenv "INJECTOR_DATA_DIR") ".injector/sessions"))

(defn- session-log-path [session-id]
  (io/file data-dir (str session-id ".jsonl")))

;; Map: session-id -> {:turns [entry ...] :at long}
(defonce ^:private session-cache (atom {}))
(def ^:private CACHE_THRESHOLD 256)

(defn- evict-lru [cache]
  (if (> (count cache) CACHE_THRESHOLD)
    (let [lru-key (key (apply min-key (comp :at val) cache))]
      (dissoc cache lru-key))
    cache))

(defn- load-session-from-disk [session-id]
  (let [path (session-log-path session-id)]
    (if (.exists path)
      (with-open [reader (io/reader path)]
        (doall (map #(json/parse-string % true) (line-seq reader))))
      [])))

(defn get-session [session-id]
  (let [now (System/currentTimeMillis)]
    (if-let [cached (get @session-cache session-id)]
      (do
        (swap! session-cache assoc-in [session-id :at] now)
        (:turns cached))
      (let [loaded (load-session-from-disk session-id)]
        (swap! session-cache
               (fn [cache]
                 (-> cache
                     (assoc session-id {:turns loaded :at now})
                     evict-lru)))
        loaded))))

(defn append-turn! [session-id entry]
  (let [valid-entry (schemas/validate-entry entry)
        path (session-log-path session-id)
        line (str (json/generate-string valid-entry) "\n")
        now (System/currentTimeMillis)]
    (io/make-parents path)
    (spit path line :append true)
    ;; Optimistic update: append to cache if present, else let get-session load it later
    (swap! session-cache
           (fn [cache]
             (if (contains? cache session-id)
               (-> cache
                   (update-in [session-id :turns] conj valid-entry)
                   (assoc-in [session-id :at] now))
               cache)))))
