(ns clj-elasticsearch.client
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [org.elasticsearch.node NodeBuilder]
           [org.elasticsearch.common.xcontent XContentFactory ToXContent$Params]
           [org.elasticsearch.common.settings ImmutableSettings]
           [org.elasticsearch.action.admin.indices.status IndicesStatusRequest]
           [org.elasticsearch.common.io FastByteArrayOutputStream]
           [org.elasticsearch.client.transport TransportClient]
           [org.elasticsearch.client.support AbstractClient]
           [org.elasticsearch.common.transport InetSocketTransportAddress]
           [org.elasticsearch.action  ActionListener]
           [org.elasticsearch.common.xcontent ToXContent]))

(def ^{:dynamic true} *client*)

(defprotocol Clojurable
  "Protocol for conversion of Response Classes to many formats"
  (convert [response format] "convert response to given format. Format can be :json, :java, or :clj"))

(defn update-settings-builder
  "creates or updates a settingsBuilder with the given hash-map"
  ([builder settings]
     (doseq [[k v] settings]
       (if (or (vector? v) (list? v))
         (.putArray builder (name k) (into-array String (map str v)))
         (.put builder (name k) (str v))))
     builder)
  ([settings]
     (update-settings-builder (ImmutableSettings/settingsBuilder) settings)))

(defn make-node
  "makes a new native node client"
  [{:keys [local-mode client-mode load-config cluster-name settings hosts]
    :or {client-mode true
         load-config false
         local-mode false
         settings {}}
    :as args}]
  (let [nodebuilder (NodeBuilder.)
        host-conf (if hosts {"discovery.zen.ping.unicast.hosts" hosts
                             "discovery.zen.ping.multicast.enabled" false}
                      {})]
    (doto nodebuilder
      (.client client-mode)
      (.local local-mode)
      (.loadConfigSettings load-config))
    (if cluster-name
      (.clusterName nodebuilder cluster-name))
    (update-settings-builder (.settings nodebuilder) (merge settings host-conf))
    (.node nodebuilder)))

(defn- make-inet-address
  [spec]
  (let [m (re-matcher #"([^\[\:]+)[\[\:]?(\d*)" spec)
        _ (.find m)
        [_ host p] (re-groups m)
        port (if (and p (not (empty? p))) (Integer/parseInt (str p)) 9300)]
    (InetSocketTransportAddress. host port)))

(defn make-transport-client
  "creates a transport client"
  [{:keys [load-config cluster-name settings hosts sniff]
    :or {client-mode true
         load-config false
         local-mode false
         settings {}
         sniff true}
    :as args}]
  (let [settings (if cluster-name (assoc settings "cluster.name" cluster-name) settings)
        conf (update-settings-builder (merge settings {"client.transport.sniff" sniff}))
        client (TransportClient. conf load-config)]
    (doseq [host hosts]
      (.addTransportAddress client (make-inet-address host)))
    client))

(defn- make-content-builder
  [& [type]]
  (case type
    :json (XContentFactory/jsonBuilder)
    :smile (XContentFactory/smileBuilder)
    (XContentFactory/smileBuilder)))

(defn- convert-xcontent
  [^org.elasticsearch.common.xcontent.ToXContent response empty-params & [format]]
  (if (= format :java)
    response
    (let [os (FastByteArrayOutputStream.)
          builder (if (= format :json)
                    (XContentFactory/jsonBuilder os)
                    (XContentFactory/smileBuilder os))]
      (.startObject builder)
      (.toXContent response builder empty-params)
      (.endObject builder)
      (.flush builder)
      (case format
        :json (.toString os "UTF-8")
        (json/decode-smile (.underlyingBytes os) true)))))

(defn- method->arg
  [method]
  (let [name (.getName method)
        parameter (first (seq (.getParameterTypes method)))
        conv (str/replace name #"^set|get" "")
        conv (str/lower-case (str/replace conv #"(\p{Lower})(\p{Upper})" "$1-$2"))
        added (if (and parameter (= parameter java.lang.Boolean/TYPE)) (str conv "?") conv)]
    added))

(defmacro def-converter
  ^{:private true}
  [fn-name class-name]
  (let [klass (Class/forName class-name)
        methods (.getMethods klass)
        getters-m (filter #(let [n (.getName %)]
                             (and (.startsWith n "get")
                                  (not (#{"getClass" "getShardFailures"} n)))) methods)
        iterator? (some #{"iterator"} (map #(.getName %) methods))
        sig (reduce (fn [acc m]
                      (let [m-name (.getName m)]
                        (assoc acc
                          (keyword (method->arg m))
                          (symbol (str "." m-name)))))
                    {} getters-m)
        response (gensym "response")]
    `(defn ~fn-name
       {:private true}
       [~(with-meta response {:tag klass}) & [format#]]
       (if (= format# :java)
         ~response
         (let [res# (hash-map
                     ~@(let [gets (for [[kw getter] sig]
                                    `(~kw (~getter ~response)))
                             gets (if iterator?
                                    (conj gets  `(:iterator (iterator-seq (.iterator ~response))))
                                    gets)]
                         (apply concat gets)))]
           (case format#
             :json (json/generate-string res#)
             res#))))))

(defn get-empty-params
  [class-name]
  (let [klass (Class/forName class-name)
        empty-params-field (first (filter #(= (.getName %) "EMPTY_PARAMS") (.getFields klass)))
        empty-params (.get empty-params-field klass)]
    empty-params))

(defmacro def-xconverter
  ^{:private true}
  [fn-name class-name]
  (let [klass (Class/forName class-name)
        response (gensym "response")]
    `(let [empty# (get-empty-params ~class-name)]
       (defn ~fn-name
         {:private true}
         [~(with-meta response {:tag klass}) & [format#]]
         (convert-xcontent ~response empty# format#)))))

(defmacro def-converters
  ^{:private true}
  [& conv-defs]
  `(do ~@(for [[nam klass typ] conv-defs]
           `(do (~(if (= typ :xcontent) 'def-xconverter 'def-converter) ~nam ~klass)
                (extend ~(symbol klass)
                  Clojurable
                  {:convert (fn [response# format#]
                              (~(symbol (str "clj-elasticsearch.client/" nam))
                               response# format#))})))))

(defn make-client
  "creates a client of given type (:node or :transport) and spec"
  [type spec]
  (case type
    :node (.client (make-node spec))
    :transport (make-transport-client spec)
    (make-transport-client spec)))

(defmacro with-node-client
  "opens a node client with given spec and executes the body before closing it"
  [server-spec & body]
  `(with-open [node# (make-node ~server-spec)]
    (binding [clj-elasticsearch.client/*client* (.client node#)]
      (do
        ~@body))))

(defmacro with-transport-client
  "opens a transport client with given spec and executes the body before closing it"
  [server-spec & body]
  `(with-open [client# (make-client :transport ~server-spec)]
    (binding [clj-elasticsearch.client/*client* client#]
      (do
        ~@body))))

(defn build-document
  "returns a string representation of a document suitable for indexing"
  [doc]
  (json/encode-smile doc))

(defn- get-index-admin-client
  [client]
  (-> client (.admin) (.indices)))

(defn- get-cluster-admin-client
  [client]
  (-> client (.admin) (.cluster)))

(defn- is-settable-method?
  [klass method]
  (let [return (.getReturnType method)
        super (.getSuperclass klass)
        allowed #{klass super}
        parameters (.getParameterTypes method)
        nb-params (alength parameters)]
    (and (allowed return) (= nb-params 1))))

(defn- is-execute-method?
  [klass method]
  (let [return (.getReturnType method)
        parameters (into #{} (seq (.getParameterTypes method)))
        nb-params (count parameters)]
    (and (contains? parameters klass) (= nb-params 1))))

(defn- get-settable-methods
  [class-name]
  (let [klass (Class/forName class-name)
        methods (.getMethods klass)
        settable (filter #(is-settable-method? klass %) (seq methods))]
    settable))

(defn- get-execute-method
  [request-class-name client-class-name]
  (let [c-klass (Class/forName client-class-name)
        r-klass (Class/forName request-class-name)
        methods (.getMethods c-klass)
        executable (first (filter #(is-execute-method? r-klass %) (seq methods)))]
    executable))

(defn- request-signature
  [class-name]
  (let [methods (get-settable-methods class-name)
        args (map method->arg methods)]
    (zipmap (map keyword args)
            methods)))

(defn- acoerce
  [val]
  (if (or (vector? val) (list? val))
    (into-array val)
    val))

(defn select-vals
  [h ks]
  (for [k ks]
    (get h k)))

(defmacro defn-request
  ^{:private true}
  [fn-name request-class-name cst-args client-class-name]
  (let [r-klass (Class/forName request-class-name)
        sig (request-signature request-class-name)
        method (get-execute-method request-class-name client-class-name)
        m-name (symbol (str "." (.getName method)))
        args (remove (into #{} cst-args) (keys sig))
        arglists [['options] ['client `{:keys [~@(map #(-> % name symbol) (conj args "listener" "format"))] :as ~'options}]]
        cst-gensym (take (count cst-args) (repeatedly gensym))
        signature (reduce (fn [acc [k v]] (assoc acc k (symbol (str "." (.getName v))))) {} sig)
        request (gensym "request")
        options (gensym "options")
        client (gensym "client")]
    `(defn
       ~fn-name
       {:doc (format "Required args: %s. Generated from class %s" ~(pr-str cst-args) ~request-class-name)
        :arglists '(~@arglists)}
       ([~client options#]
          (let [client# ~@(case client-class-name
                            "org.elasticsearch.client.internal.InternalClient" `(~client)
                            "org.elasticsearch.client.IndicesAdminClient"
                            `((get-index-admin-client ~client))
                            "org.elasticsearch.client.ClusterAdminClient"
                            `((get-cluster-admin-client ~client)))
                [~@cst-gensym] (map acoerce (select-vals options# [~@cst-args]))
                ~request (new ~r-klass ~@cst-gensym)
                ~options (dissoc options# ~@cst-args)]
            ~@(for [[k met] signature] `(when (contains?  ~options ~k)
                                          (~met ~request (acoerce (get ~options ~k)))))
            (cond
             (get ~options :debug) ~request
             (get ~options :listener) (~m-name client# ~request (:listener ~options))
             :else (convert (.actionGet (~m-name client# ~request)) (:format ~options)))))
       ([options#]
          (~fn-name *client* options#)))))

(defmacro def-requests
  ^{:private true}
  [client-class-name & request-defs]
  `(do ~@(map (fn [req-def]
                `(defn-request ~@(concat req-def [client-class-name])))
              request-defs)))

(defn- convert-source
  [src]
  (cond
   (instance? java.util.HashMap src) (into {} (map (fn [^java.util.Map$Entry e] [(.getKey e)
                                                           (convert-source (.getValue e))]) src))
   (instance? java.util.ArrayList src) (into [] (map convert-source src))
   :else src))

(defn- convert-fields
  [^java.util.HashMap hm]
  (into {} (map (fn [^org.elasticsearch.index.get.GetField f]
                  [(.getName f) (convert-source (.getValue f))]) (.values hm))))

(defn- convert-get
  [^org.elasticsearch.action.get.GetResponse response & [format]]
  (if (= format :java)
    response
    (let [data (if (.exists response)
                 {:_index (.getIndex response)
                  :_type (.getType response)
                  :_id (.getId response)
                  :_version (.getVersion response)})
          data (if-not (.isSourceEmpty response)
                 (assoc data :_source (convert-source (.sourceAsMap response)))
                 data)
          data (let [fields (.getFields response)]
                 (if-not (empty? fields)
                   (assoc data :fields (convert-fields fields))
                   data))]
      (if (= format :json)
        (json/generate-string data)
        data))))

(def-converters
  (convert-indices-status "org.elasticsearch.action.admin.indices.status.IndicesStatusResponse" :xcontent)
  (convert-analyze "org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse" :xcontent)
  (convert-search "org.elasticsearch.action.search.SearchResponse" :xcontent)
  (convert-count "org.elasticsearch.action.count.CountResponse" :object)
  (convert-delete "org.elasticsearch.action.delete.DeleteResponse" :object)
  (convert-delete-by-query "org.elasticsearch.action.deletebyquery.DeleteByQueryResponse" :object)
  (convert-index "org.elasticsearch.action.index.IndexResponse" :object)
  (convert-percolate "org.elasticsearch.action.percolate.PercolateResponse" :object)
  (convert-optimize "org.elasticsearch.action.admin.indices.optimize.OptimizeResponse" :object)
  (convert-analyze "org.elasticsearch.action.admin.indices.analyze.AnalyzeResponse" :object)
  (convert-clear-cache "org.elasticsearch.action.admin.indices.cache.clear.ClearIndicesCacheResponse" :object)
  (convert-create-index "org.elasticsearch.action.admin.indices.create.CreateIndexResponse" :object)
  (convert-delete-index "org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse" :object)
  (convert-delete-mapping "org.elasticsearch.action.admin.indices.mapping.delete.DeleteMappingResponse" :object)
  (convert-exists-index "org.elasticsearch.action.admin.indices.exists.IndicesExistsResponse" :object)
  (convert-flush-request "org.elasticsearch.action.admin.indices.flush.FlushResponse" :object)
  (convert-gateway-snapshot "org.elasticsearch.action.admin.indices.gateway.snapshot.GatewaySnapshotResponse" :object)
  (convert-put-mapping "org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse" :object)
  (convert-put-template "org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateResponse" :object)
  (convert-refresh-index "org.elasticsearch.action.admin.indices.refresh.RefreshResponse" :object)
  (convert-update-index-settings "org.elasticsearch.action.admin.indices.settings.UpdateSettingsResponse" :object)
  (convert-cluster-health "org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse" :object)
  (convert-node-info "org.elasticsearch.action.admin.cluster.node.info.NodesInfoResponse" :object)
  (convert-node-restart "org.elasticsearch.action.admin.cluster.node.restart.NodesRestartResponse" :object)
  (convert-node-shutdown "org.elasticsearch.action.admin.cluster.node.shutdown.NodesShutdownResponse" :object)
  (convert-node-stats "org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse" :object)
  (convert-update-cluster-settings "org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsResponse" :object))

(extend-type org.elasticsearch.action.get.GetResponse
   Clojurable
   (convert [response format] (convert-get response format)))

(def-requests "org.elasticsearch.client.internal.InternalClient"
  (index-doc "org.elasticsearch.action.index.IndexRequest" [])
  (search "org.elasticsearch.action.search.SearchRequest" [])
  (get-doc "org.elasticsearch.action.get.GetRequest" [:index])
  (count-docs "org.elasticsearch.action.count.CountRequest" [:indices])
  (delete-doc "org.elasticsearch.action.delete.DeleteRequest" [])
  (delete-by-query "org.elasticsearch.action.deletebyquery.DeleteByQueryRequest" [])
  (more-like-this "org.elasticsearch.action.mlt.MoreLikeThisRequest" [:index])
  (percolate "org.elasticsearch.action.percolate.PercolateRequest" [])
  (scroll "org.elasticsearch.action.search.SearchScrollRequest" [:scroll-id]))

(def-requests "org.elasticsearch.client.IndicesAdminClient"
  (optimize-index "org.elasticsearch.action.admin.indices.optimize.OptimizeRequest" [])
  (analyze-request "org.elasticsearch.action.admin.indices.analyze.AnalyzeRequest" [:index :text])
  (clear-index-cache "org.elasticsearch.action.admin.indices.cache.clear.ClearIndicesCacheRequest" [:indices])
  (close-index "org.elasticsearch.action.admin.indices.close.CloseIndexRequest" [:index])
  (create-index "org.elasticsearch.action.admin.indices.create.CreateIndexRequest" [:index])
  (delete-index "org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest" [:indices])
  (delete-mapping "org.elasticsearch.action.admin.indices.mapping.delete.DeleteMappingRequest" [:indices])
  (delete-template "org.elasticsearch.action.admin.indices.template.delete.DeleteIndexTemplateRequest" [:name])
  (exists-index "org.elasticsearch.action.admin.indices.exists.IndicesExistsRequest" [:indices])
  (flush-index "org.elasticsearch.action.admin.indices.flush.FlushRequest" [:indices])
  (gateway-snapshot "org.elasticsearch.action.admin.indices.gateway.snapshot.GatewaySnapshotRequest" [:indices])
  (put-mapping "org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest" [:indices])
  (put-template "org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest" [:name])
  (refresh-index "org.elasticsearch.action.admin.indices.refresh.RefreshRequest" [:indices])
  (index-segments "org.elasticsearch.action.admin.indices.segments.IndicesSegmentsRequest" [])
  (index-stats "org.elasticsearch.action.admin.indices.stats.IndicesStatsRequest" [])
  (index-status "org.elasticsearch.action.admin.indices.status.IndicesStatusRequest" [])
  (update-index-settings "org.elasticsearch.action.admin.indices.settings.UpdateSettingsRequest" [:indices]))

(def-requests "org.elasticsearch.client.ClusterAdminClient"
  (cluster-health "org.elasticsearch.action.admin.cluster.health.ClusterHealthRequest" [:indices])
  (node-info "org.elasticsearch.action.admin.cluster.node.info.NodesInfoRequest" [])
  (node-restart "org.elasticsearch.action.admin.cluster.node.restart.NodesRestartRequest" [:nodes-ids])
  (node-shutdown "org.elasticsearch.action.admin.cluster.node.shutdown.NodesShutdownRequest" [:nodes-ids])
  (nodes-stats "org.elasticsearch.action.admin.cluster.node.stats.NodesStatsRequest" [:nodes-ids])
  (update-cluster-settings "org.elasticsearch.action.admin.cluster.settings.ClusterUpdateSettingsRequest" []))

(defn make-listener
  "makes a listener suitable as a callback for requests"
  [{:keys [on-failure on-response]}]
  (proxy [ActionListener] []
    (onFailure [e] (on-failure e))
    (onResponse [r] (on-response r))))