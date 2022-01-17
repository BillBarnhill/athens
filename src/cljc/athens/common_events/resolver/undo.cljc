(ns athens.common-events.resolver.undo
  (:require
    [athens.common-db                     :as common-db]
    [athens.common-events                 :as common-events]
    [athens.common-events.graph.atomic    :as atomic-graph-ops]
    [athens.common-events.graph.composite :as composite]
    [athens.common-events.graph.ops       :as graph-ops]
    [athens.common.logging                :as log]
    [athens.common-events.graph.atomic    :as atomic))


(defn undo?
  [event]
  (-> event :event/op :op/trigger :op/undo))


;; Impl according to https://github.com/athensresearch/athens/blob/main/doc/adr/0021-undo-redo.md#approach
(defmulti resolve-atomic-op-to-undo-ops
  #(:op/type %3))


(defmethod resolve-atomic-op-to-undo-ops :block/save
  [db evt-db {:op/keys [args]}]
  (let [{:block/keys [uid]}    args
        {:block/keys [string]} (common-db/get-block evt-db [:block/uid uid])]
    [(graph-ops/build-block-save-op db uid string)]))

(defmethod resolve-atomic-op-to-undo-ops :block/move
  [evt-db {:op/keys [args]}]
  (println "resolve atomic op to undo ops args -->" args)
  (let [{:block/keys [uid]}       args
        {:block/keys [order]}     (common-db/get-block evt-db [:block/uid uid])
        ;; How to get the relative position of a block?
        ;; - If the block has 0 block-order then we can say it is children of some block
        ;;   hence we can reference its position via parent
        ;; - If the block has order > 0 then we know it atleast a previous sibling so we
        ;;   can use this previous block to reference the position
        parent                    (common-db/get-parent evt-db [:block/uid uid])
        parent-page?              (if (:node/title parent)
                                    true
                                    false)
        parent-ref                (if parent-page?
                                    (:node/title parent)
                                    (:block/uid parent))
        {prev-sib-uid :block/uid} (common-db/nth-sibling evt-db uid -1)
        prev-block-pos            (cond
                                    (= order 0) :parent
                                    :else       :prev-sib)
        position                  (cond
                                    (true? parent-page?)         {:page/title parent-ref
                                                                  :relation   :first}
                                    (= :parent   prev-block-pos) {:block/uid parent-ref
                                                                  :relation  :first}
                                    (= :prev-sib prev-block-pos) {:block/uid prev-sib-uid
                                                                  :relation  :after})]
    [(atomic/make-block-move-op uid position)]))

(defmethod resolve-atomic-op-to-undo-ops :block/open
  [_db evt-db {:op/keys [args]}]
  (let [{:block/keys [uid]}    args
        {:block/keys [open]} (common-db/get-block evt-db [:block/uid uid])]
    [(atomic-graph-ops/make-block-open-op uid open)]))


(defmethod resolve-atomic-op-to-undo-ops :composite/consequence
  [db evt-db {:op/keys [consequences] :as op}]
  [(assoc op :op/consequences (mapcat (partial resolve-atomic-op-to-undo-ops db evt-db)
                                      consequences))])


;; TODO: should there be a distinction between undo and redo?
(defn build-undo-event
  [db evt-db {:event/keys [id type op] :as event}]
  (log/debug "build-undo-event" event)
  (if-not (contains? #{:op/atomic} type)
    (throw (ex-info "Cannot undo non-atomic event" event))
    (->> op
         (resolve-atomic-op-to-undo-ops db evt-db)
         (composite/make-consequence-op {:op/undo id})
         common-events/build-atomic-event)))

