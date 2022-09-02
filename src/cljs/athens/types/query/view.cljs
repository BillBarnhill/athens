(ns athens.types.query.view
  "Views for Athens Tasks"
  (:require
    ["/components/Block/BlockFormInput" :refer [BlockFormInput]]
    ["/components/DnD/DndContext" :refer [DragAndDropContext]]
    ["/components/DnD/Droppable" :refer [Droppable]]
    ["/components/DnD/Sortable" :refer [Sortable]]
    ["/components/Icons/Icons" :refer [GraphChildIcon ArrowRightOnBoxIcon PlusIcon]]
    ["/components/ModalInput/ModalInput" :refer [ModalInput]]
    ["/components/ModalInput/ModalInputPopover" :refer [ModalInputPopover]]
    ["/components/ModalInput/ModalInputTrigger" :refer [ModalInputTrigger]]
    ["/components/Query/KanbanBoard" :refer [KanbanBoard
                                             KanbanCard
                                             KanbanSwimlane
                                             KanbanColumn]]
    ["/components/Query/Query" :refer [QueryRadioMenu]]
    ["/components/Query/Table" :refer [QueryTable]]
    ["@chakra-ui/react" :refer [Box,
                                IconButton
                                HStack
                                Heading
                                ButtonGroup
                                Flex
                                VStack
                                HStack
                                Text]]
    ["@dnd-kit/core" :refer [closestCorners,
                             DragOverlay,]]
    ["@dnd-kit/sortable" :refer [SortableContext,
                                 verticalListSortingStrategy,]]
    [athens.common-db :as common-db]
    [athens.common-events :as common-events]
    [athens.common-events.bfs :as bfs]
    [athens.common-events.graph.composite :as composite]
    [athens.common-events.graph.ops :as graph-ops]
    [athens.common.utils :as utils]
    [athens.dates :as dates]
    [athens.db :as db]
    [athens.parse-renderer :as parse-renderer]
    [athens.reactive :as reactive]
    [athens.router :as router]
    [athens.self-hosted.presence.views          :as presence]
    [athens.types.core :as types]
    [athens.types.dispatcher :as dispatcher]
    [athens.views.blocks.editor                 :as editor]
    [clojure.string :refer []]
    [re-frame.core :as rf]
    [athens.types.query.table :refer [QueryTableV2]]
    [athens.types.query.kanban :refer [DragAndDropKanbanBoard]]
    [athens.types.query.shared :as shared]
    [reagent.core :as r]))



;; CONSTANTS


(def base-schema
  [":block/uid" ":create/auth" ":create/time" ":last-edit/auth" ":last-edit/time"])


(def SCHEMA
  ;; "[[athens/comment-thread]]" (concat base-schema [])})
  {"[[athens/task]]"           (concat [":task/title" ":task/page" ":task/status" ":task/assignee" ":task/priority" ":task/due-date"] base-schema)})


(def AUTHORS
  ["None" "Sid" "Jeff" "Stuart" "Filipe" "Alex"])


(def LAYOUTS
  ["table" "board" #_"list"])


(def SORT_DIRECTIONS
  ["asc" "desc"])


(def SPECIAL_FILTERS
  ["None" "On this page"])


(def GROUP_BY_OPTIONS
  [":task/page" ":task/status" ":task/assignee" ":task/priority" ":create/auth"])


(def DEFAULT-PROPS
  {":entity/type"                   "[[athens/query]]"
   "athens/query/layout"            "table"
   "athens/query/select"            "[[athens/comment-thread]]"
   "athens/query/filter/author"     "None"
   "athens/query/filter/special"    "None"
   "athens/query/sort/by"           ":create/time"
   "athens/query/sort/direction"    "desc"
   "athens/query/group/by"          ":create/auth"
   "athens/query/group/subgroup/by" ":create/auth"
   "athens/query/properties/hide"   {}
   "athens/query/properties/order"  nil})


(defn get*
  [hm ks]
  (->> (map #(str "athens/query/" %) ks)
       (map #(get hm %))))

(defn get-schema
  [k]
  (or (get SCHEMA k) base-schema))


(def ENTITY_TYPES
  (keys SCHEMA))


;; Helpers


(defn nested-group-by
  "You have to pass the first group"
  [kw columns]
  (->> (map (fn [[k v]]
              [k (group-by #(get % kw) v)])
            columns)
       (into (hash-map))))

(defn group-stuff
  [g sg items]
  (->> items
       (group-by #(get % sg))
       (nested-group-by g)))


(defn tasks-to-trees
  [tasks]
  (reduce (fn [m [uid parents]]
            (if (seq parents)
              (assoc-in m parents {uid {}})
              (assoc m uid {})))
          {}
          tasks))


(defn context-to-block-properties
  [context]
  (apply hash-map
         (->> context
              (map (fn [[k v]]
                     [k #:block{:string v
                                :uid    (utils/gen-block-uid)}]))

              flatten)))


(defn new-card
  "new-card needs to know the context of where it was pressed. For example, pressing it in a given column and swimlane
  would pass along those properties to the new card. Filter conditions would also be passed along. It doesn't matter if
  inherited properties are passed throughu group, subgroup, or filters. It just matters that they are true, and the view should be derived properly.

  context == {:task/status 'todo'
              :task/project '[[Project: ASD]]'"
  [context f-special query-uid]
  (let [context             (js->clj context)
        new-block-props     (context-to-block-properties context)
        parent-of-new-block (if (= f-special "On this page")
                              {:block/uid query-uid}
                              {:page/title (-> (dates/get-day) :title)})
        position            (merge {:relation :last} parent-of-new-block)
        evt                 (->> (bfs/internal-representation->atomic-ops
                                   @athens.db/dsdb
                                   [#:block{:uid        (utils/gen-block-uid)
                                            :string     ""
                                            :properties (merge {":entity/type" #:block{:string "[[athens/task]]"
                                                                                       :uid    (utils/gen-block-uid)}
                                                                ":task/title" #:block{:string "Untitled task"
                                                                                      :uid    (utils/gen-block-uid)}}
                                                               new-block-props)}]
                                   position)
                                 (composite/make-consequence-op {:op/type :new-type})
                                 common-events/build-atomic-event)]
    (re-frame.core/dispatch [:resolve-transact-forward evt])))


;; UPDATE

;; update task stuff

(defn update-card-container
  [id active-container-context over-container-context]
  (let [{active-swimlane-id :swimlane-id active-column-id :column-id} active-container-context
        {over-swimlane-id :swimlane-id over-column-id :column-id} over-container-context
        diff-column?   (not= active-column-id over-column-id)
        diff-swimlane? (not= active-swimlane-id over-swimlane-id)
        nil-swimlane?  (= over-swimlane-id "None")
        nil-column?    (= over-column-id "None")
        new-column     (str "((" over-column-id "))")]
    (when diff-swimlane?
      (rf/dispatch [:graph/update-in [:block/uid id] [":task/assignee"]
                    (fn [db prop-uid]
                      [(if nil-swimlane?
                         (graph-ops/build-block-remove-op db prop-uid)
                         (graph-ops/build-block-save-op db prop-uid over-swimlane-id))])]))
    (when diff-column?
      (rf/dispatch [:graph/update-in [:block/uid id] [":task/status"]
                    (fn [db prop-uid]
                      [(if nil-column?
                         (graph-ops/build-block-remove-op db prop-uid)
                         (graph-ops/build-block-save-op db prop-uid new-column))])]))))

#_(defn update-status
    [id new-status]
    (rf/dispatch [:graph/update-in [:block/uid id] [":task/status"]
                  (fn [db prop-uid]
                    [(graph-ops/build-block-save-op db prop-uid new-status)])]))


;; All commented out for when we modify kanban columns
#_(defn new-kanban-column
    "This creates a new block/child at the property/values key, but the kanban board doesn't trigger a re-render because it isn't aware of property/values yet."
    [group-by-id]
    (rf/dispatch [:graph/update-in [:node/title group-by-id] [":property/values"]
                  (fn [db prop-uid]
                    [(graph-ops/build-block-new-op db (utils/gen-block-uid) {:block/uid prop-uid :relation :last})])]))


#_(defn update-many-properties
    [db key value new-value]
    (->> (common-db/get-instances-of-key-value db key value)
         (map #(get-in % [key :block/uid]))
         (map (fn [uid]
                (graph-ops/build-block-save-op db uid new-value)))))


#_(defn update-kanban-column
    "Update the property page that is the source of values for a property.
  Also update all the blocks that are using that property."
    [property-key property-value new-value]
    (rf/dispatch [:graph/update-in [:node/title property-key] [":property/values"]
                  (fn [db prop-uid]
                    (let [{:block/keys [children]} (common-db/get-block-document db [:block/uid prop-uid])
                          update-uid (->> children
                                          (map (fn [{:block/keys [string uid]}] [string uid]))
                                          (filter #(= (first %) property-value))
                                          (first)
                                          second)
                          ;; update all blocks that match key:value to key:new-value
                          update-ops (update-many-properties db property-key property-value new-value)]

                      (vec (concat [(graph-ops/build-block-save-op db update-uid new-value)]
                                   update-ops))))]))


;; update properties

(defn update-query-property
  [uid key new-value]
  (let [namespaced-key (str "athens/query/" key)]
    (rf/dispatch [:graph/update-in [:block/uid uid] [namespaced-key]
                  (fn [db prop-uid]
                    [(graph-ops/build-block-save-op db prop-uid new-value)])])))


#_(defn toggle-hidden-property
    "If property is hidden, remove key. Otherwise, add property key."
    [id hidden-property-id]
    (js/alert "not implemented")
    #_(rf/dispatch [:graph/update-in [:block/uid id] ["athens/query/properties/hide" hidden-property-id]
                    (fn [db hidden-prop-uid]
                      (let [property-hidden? (common-db/block-exists? db [:block/uid hidden-prop-uid])]
                        [(if property-hidden?
                           (graph-ops/build-block-remove-op @db/dsdb hidden-prop-uid)
                           (graph-ops/build-block-save-op db hidden-prop-uid ""))]))]))


(defn order-children
  [children]
  (->> (sort-by :block/order children)))


(defn get-query-props
  [properties]
  (->> properties
       (reduce-kv
         (fn [acc k {:block/keys [children string] nested-properties :block/properties :as _v}]
           (assoc acc k (cond
                          (and (seq children) (not (clojure.string/blank? string))) {:key string :values (order-children children)}
                          (seq children) (order-children children)
                          nested-properties (reduce-kv (fn [acc k v]
                                                         (assoc acc k (:block/string v)))
                                                       {}
                                                       nested-properties)
                          :else string)))
         {})
       (merge DEFAULT-PROPS)))


(defn get-reactive-property
  [eid property-key]
  (let [property-page (reactive/get-reactive-block-document eid)
        property      (get-in property-page [:block/properties property-key])
        {:block/keys [children properties _string]} property]
    (cond
      (seq children) (->> children
                          order-children
                          (mapv (fn [{:block/keys [uid string]}]
                                  #:block{:uid uid :string string})))
      (seq properties) (keys properties))))


(defn sort-dir-fn
  [query-sort-direction]
  (if (= query-sort-direction "asc")
    compare
    (comp - compare)))


(defn sort-table
  [query-data query-sort-by query-sort-direction]
  (->> query-data
       (sort-by #(get % query-sort-by)
                (sort-dir-fn query-sort-direction))))


#_(defn str-to-title
    [s]
    (str "[[" s "]]"))


;; Views


(defn options-el
  [{:keys [_properties parsed-properties uid schema]}]
  (let [[layout select _p-order _p-hide f-author f-special s-by s-direction g-by g-s-by]
        (get* parsed-properties ["layout" "select" "properties/order" "properties/hide" "filter/author" "filter/special" "sort/by" "sort/direction" "group/by" "group/subgroup/by"])
        s-by       (shared/parse-for-title s-by)
        menus-data [{:heading  "Entity Type"
                     :options  ENTITY_TYPES
                     :onChange #(update-query-property uid "select" %)
                     :value    select}
                    {:heading  "Layout"
                     :options  LAYOUTS
                     :onChange #(update-query-property uid "layout" %)
                     :value    layout}
                    {:heading  "Filter By Author"
                     :options  AUTHORS
                     :onChange #(update-query-property uid "filter/author" %)
                     :value    f-author}
                    {:heading  "Special Filters"
                     :options  SPECIAL_FILTERS
                     :onChange #(update-query-property uid "filter/special" %)
                     :value    f-special}
                    {:heading  "Sort By"
                     :options  schema
                     :onChange #(update-query-property uid "sort/by" %)
                     :value    s-by}
                    {:heading  "Sort Direction"
                     :options  SORT_DIRECTIONS
                     :onChange #(update-query-property uid "sort/direction" %)
                     :value    s-direction}
                    {:heading "Group By (Board)"
                     :options GROUP_BY_OPTIONS
                     :onChange #(update-query-property uid "group/by" %)
                     :value g-by}
                    {:heading "Subgroup By (Board)"
                     :options GROUP_BY_OPTIONS
                     :onChange #(update-query-property uid "group/subgroup/by" %)
                     :value g-s-by}]]
    [:> ButtonGroup {:isAttached true :size "xs"}
     (for [menu menus-data]
       (let [{:keys [heading options onChange value]} menu]
         [:> QueryRadioMenu {:key heading :heading heading :options options :onChange onChange :value value}]))]))


(defn update-card-field
  [id k new-value]
  (rf/dispatch [:graph/update-in [:block/uid id] [k]
                (fn [db prop-uid]
                  [(graph-ops/build-block-save-op db prop-uid new-value)])]))



(defn- find-container-id
  "Accepts event.active or event.over"
  [e active-or-over]
  (try
    (case active-or-over
      :active (.. e -active -data -current -sortable -containerId)
      :over (.. e -over -data -current -sortable -containerId))
    (catch js/Object _
      (case active-or-over
        :active (.. e -active -id)
        :over (.. e -over -id)))))


(defn- get-container-context
  [container-id]
  (let [swimlane-id (-> (re-find #"swimlane-(@?\w+)" container-id) second)
        column-id   (-> (re-find #"column-(\w+)" container-id) second)]
    {:swimlane-id swimlane-id
     :column-id column-id}))







(defn query-el
  [{:keys [query-data parsed-properties uid schema]}]
  (let [query-uid uid
        [_select layout s-by s-direction f-author f-special _p-order p-hide]
        (get* parsed-properties ["select" "layout" "sort/by" "sort/direction" "filter/author" "filter/special" "properties/order" "properties/hide"])
        s-by              (shared/parse-for-title s-by)
        filter-author-fn  (fn [x]
                            (let [entity-author (get x ":create/auth")]
                              (or (= f-author "None")
                                  (= f-author
                                     entity-author))))
        special-filter-fn (fn [x]
                            (cond
                              (= f-special "On this page")
                              (let [comment-uid           (get x ":block/uid")
                                    comments-parent-page  (-> (db/get-root-parent-page comment-uid)
                                                              :node/title)
                                    current-page-of-query (-> (db/get-root-parent-page uid)
                                                              :node/title)]
                                (= comments-parent-page current-page-of-query))
                              :else true))
        query-data        (filterv special-filter-fn query-data)
        query-data        (filterv filter-author-fn query-data)
        query-data        (sort-table query-data s-by s-direction)]
    [:> VStack {:className "query-el" :key query-uid :align "stretch"}
     (case layout
       "board"
       (let [[g-by sg-by] (get* parsed-properties ["group/by" "group/subgroup/by"])
             query-group-by                (shared/parse-for-title g-by)
             query-subgroup-by             (shared/parse-for-title sg-by)
             all-possible-group-by-columns (concat [{:block/string "None" :block/uid nil}]
                                                   (get-reactive-property [:node/title query-group-by] ":property/enum"))
             boardData                     (if (and query-subgroup-by query-group-by)
                                             (group-stuff query-group-by query-subgroup-by query-data)
                                             (group-by #(get % query-group-by) query-data))]

         [DragAndDropKanbanBoard {:query-uid                     query-uid
                                  :f-special                     f-special
                                  :boardData                     boardData
                                  :all-possible-group-by-columns all-possible-group-by-columns
                                  :groupBy                       g-by
                                  :subgroupBy                    sg-by}])

       "list"
       [:div "TODO"]

       "table"
       (let [get-parents           (fn [uid]
                                     (let [parent-uids (->> (db/get-parents-recursively [:block/uid uid])
                                                            (mapv :block/uid))]
                                       [uid parent-uids]))
             sort-by-parents-count (fn [x] (-> x second count))
             tasks                 (->> (reactive/get-reactive-instances-of-key-value ":entity/type" "[[athens/task]]")
                                        ;;query-data
                                        (mapv :block/uid)
                                        (mapv get-parents)
                                        (sort-by sort-by-parents-count))
             task-trees         (tasks-to-trees tasks)]

         [QueryTableV2 {:data task-trees
                        :columns ["Title" "Status" "Priority" "Assignee" "Due Date"]}]))]))



(comment "current shape of data for query kanban boards"
         ;; e.g.
         {"person" {"status" [{"task 1" 1}]}}
         ;; aka
         {"subgroup" {"group" ["card 1"]}}

         [:map [:map [:vector [:maps]]]]

         {nil {nil [{":block/uid" "1f29dce5b", ":block/string" "test", ":entity/type" "[[athens/task]]", ":create/auth" "Sid", ":create/time" 1660894009080, ":last-edit/auth" nil, ":last-edit/time" 1661011262703, ":task/page" "August 19, 2022"}
                    {":block/uid" "08-16-2022", ":block/string" nil, ":entity/type" "[[athens/task]]", ":create/auth" "Sid", ":create/time" 1660624243084, ":last-edit/auth" nil, ":last-edit/time" 1660624292449, ":task/page" "August 16, 2022"}]},
          "" {nil [{":block/string" "", ":create/time" 1660904634520, ":create/auth" "Sid", ":last-edit/time" 1661013113312, ":entity/type" "[[athens/task]]", ":last-edit/auth" nil, ":task/page" "August 19, 2022", ":block/uid" "13f68a3b4", ":task/due-date" "asd", ":task/assignee" ""}]},
          "[[@Filipe]]" {"((326893972))" [{":block/string" "", ":create/time" 1661245796014, ":create/auth" "Jeff", ":last-edit/time" 1661245843288, ":entity/type" "[[athens/task]]", ":last-edit/auth" nil, ":task/priority" "((3ae3f4da9))", ":task/page" "Project: Tasks", ":block/uid" "6e2d0b69d", ":task/title" "design api", ":task/due-date" "[[August 22, 2022]] ", ":task/assignee" "[[@Filipe]]", ":task/status" "((326893972))"}]},
          "[[@Jeff]]" {"((5f282d535))" [{":block/string" "", ":create/time" 1661245784436, ":create/auth" "Jeff", ":last-edit/time" 1661245940745, ":entity/type" "[[athens/task]]", ":last-edit/auth" nil, ":task/priority" "((c45df8496))", ":task/page" "Project: Tasks", ":block/uid" "37fcd71e9", ":task/title" "Design UIs", ":task/due-date" "[[August 22, 2022]] ", ":task/assignee" "[[@Jeff]]", ":task/status" "((5f282d535))"}]},
          "[[@Sid]]" {"((c09f1865b))" [{":block/string" "", ":create/time" 1661245808997, ":create/auth" "Jeff", ":last-edit/time" 1661269630733, ":entity/type" "[[athens/task]]", ":last-edit/auth" nil, ":task/priority" "((abf97f9bc))", ":task/page" "Project: Tasks", ":block/uid" "c8c798cea", ":task/title" "announce the release on twitter", ":task/due-date" "[[August 22, 2022]] ", ":task/assignee" "[[@Sid]]", ":task/status" "((c09f1865b))"}]}}

         ;; there are better data structs for this too that i want to try
         ;; like  more consistency of data structs. always use map or always use vector
         [:map [:map [:map [:map [:vector [:maps]]]]]]
         {"group-id-1" {"id" "group-id-1"
                        "subgroups" {"subgroup-id-1" {"id" "subgroup-id-1"
                                                      "cards" [{} {}]}}}}


         ;; or changing the order of group-by/subgroup-by
         {"group-id" {"subgroup-id" [{:card 1} {:card 2}]}})


(comment "idk how to solve"
         ["ordering of cards"
          "ordering of columns"
          "vectors vs maps"])


(defn invalid-query?
  [parsed-props]
  (let [[layout group-by] (get* parsed-props ["layout" "group/by"])]
    (and (= layout "board")
         (nil? group-by))))


;; TODO: fix properties
;; clicking on them can add an SVG somehow
;; and then if there are block/children, it is no bueno


(defn query-block
  [block-data]
  (let [block-uid         (:block/uid block-data)
        properties        (:block/properties block-data)
        parsed-properties (get-query-props properties)
        [select] (get* parsed-properties ["select"])
        schema            (get-schema select)
        query-data        (->> (reactive/get-reactive-instances-of-key-value ":entity/type" select)
                               (map shared/block-to-flat-map)
                               (map shared/get-root-page))]

    (if (invalid-query? parsed-properties)
      [:> Box {:color "red"} "invalid query"]
      [:<>
       [options-el {:parsed-properties parsed-properties
                    :properties        properties
                    :schema            schema
                    :query-data        query-data
                    :uid               block-uid}]
       [query-el {:query-data        query-data
                  :uid               block-uid
                  :schema            schema
                  :parsed-properties parsed-properties}]])))


(defrecord QueryView
  []

  types/BlockTypeProtocol

  (inline-ref-view
    [_this _block-data _attr _ref-uid _uid _callbacks _with-breadcrumb?])


  (outline-view
    [_this block-data _callbacks]
    (let [block-uid (:block/uid block-data)]
      (fn [_block-data _callbacks]
        (let [block (-> [:block/uid block-uid] reactive/get-reactive-block-document)]
          [query-block block]))))


  (supported-transclusion-scopes
    [_this])


  (transclusion-view
    [_this _block-el _block-uid _callback _transclusion-scope])


  (zoomed-in-view
    [_this _block-data _callbacks])


  (supported-breadcrumb-styles
    [_this])


  (breadcrumbs-view
    [_this _block-data _callbacks _breadcrumb-style]))


(defmethod dispatcher/block-type->protocol "[[athens/query]]" [_k _args-map]
  (QueryView.))
