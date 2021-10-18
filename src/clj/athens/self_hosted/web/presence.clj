(ns athens.self-hosted.web.presence
  (:require
    [athens.common-events       :as common-events]
    [athens.common.logging      :as log]
    [athens.self-hosted.clients :as clients]
    [clojure.set                :as set]
    [clojure.string             :as str]
    [datascript.core            :as d]))


(let [max-id (atom 0)]
  (defn next-id
    []
    (swap! max-id inc)))


(defonce all-presence (atom {}))


(def supported-event-types
  #{:presence/hello
    :presence/editing
    :presence/rename
    :presence/goodbye})


(defn- valid-password
  [conn channel id {:keys [username]}]
  (let [max-tx (:max-tx @conn)]
    (log/info channel "New Client Intro:" username)
    (clients/add-client! channel username)

    (let [datoms (map ; Convert Datoms to just vectors.
                  (comp vec seq)
                  (d/datoms @conn :eavt))]
      (log/debug channel "Sending" (count datoms) "eavt")
      (clients/send! channel
                     (common-events/build-db-dump-event max-tx datoms))
      (clients/send! channel
                     (common-events/build-presence-all-online-event max-tx
                                                                    (clients/get-clients-usernames)))
      (doseq [{username :username
               block-uid :block/uid} (vals @all-presence)]
        (when block-uid
          (let [broadcast-presence-editing-event
                (common-events/build-presence-broadcast-editing-event max-tx username block-uid)]
            (clients/send! channel broadcast-presence-editing-event))))
      (clients/broadcast! (common-events/build-presence-online-event max-tx
                                                                     username)))

    ;; TODO Recipe for diff/patch updating client
    ;; 1. query for tx-ids since `last-tx`
    ;; 2. query for all eavt touples from 1.
    ;; 3. send! to client

    ;; confirm
    (common-events/build-event-accepted id max-tx)))


(defn- invalid-password
  [channel id {:keys [username]}]
  (log/warn channel "Invalid password in hello for username:" username)
  (common-events/build-event-rejected id
                                      "You shall not pass"
                                      {:password-error :invalid-password}))


(defn hello-handler
  [conn server-password channel {:event/keys [id args _last-tx]}]
  (let [{:keys [password]} args]
    (if (or (str/blank? server-password)
            (= server-password password))
      (valid-password conn channel id args)
      (invalid-password channel id args))))


(defn editing-handler
  [conn channel {:event/keys [id args]}]
  (let [username            (clients/get-client-username channel)
        {:keys [block-uid]} args
        max-tx              (:max-tx @conn)]
    (when block-uid
      (let [broadcast-presence-editing-event (common-events/build-presence-broadcast-editing-event max-tx username block-uid)]
        (swap! all-presence assoc username {:username username
                                            :block/uid block-uid})
        (clients/broadcast! broadcast-presence-editing-event)
        (common-events/build-event-accepted id max-tx)))))


(defn rename-handler
  [conn channel {:event/keys [id args]}]
  (let [{:keys
         [current-username
          new-username]}         args
        max-tx                   (:max-tx @conn)
        broadcast-rename-event (common-events/build-presence-broadcast-rename-event max-tx
                                                                                    current-username
                                                                                    new-username)]

    (swap! all-presence (fn [all]
                          (-> all
                              (update-in [:presence :users] set/rename-keys {current-username new-username})
                              (update-in [:presence :users new-username] assoc :username new-username))))
    (clients/add-client! channel new-username)
    (clients/broadcast! broadcast-rename-event)
    (common-events/build-event-accepted id max-tx)))


(defn goodbye-handler
  [conn username]
  (let [presence-offline-event (athens.common-events/build-presence-offline-event (:max-tx @conn) username)]
    (swap! all-presence dissoc username)
    (clients/broadcast! presence-offline-event)))


(defn presence-handler
  [conn server-password channel {:event/keys [type] :as event}]
  (condp = type
    :presence/hello   (hello-handler conn server-password channel event)
    :presence/editing (editing-handler conn channel event)
    :presence/rename (rename-handler conn channel event)
    ;; presence/goodbye is called on client close.
    ))
