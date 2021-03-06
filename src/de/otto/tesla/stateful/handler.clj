(ns de.otto.tesla.stateful.handler
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [metrics.core :as mcore])
  (:import (java.util.concurrent TimeUnit)
           (java.net URI)
           (com.codahale.metrics MetricRegistry SlidingTimeWindowReservoir Timer)))

(defn- sliding-window-timer [reporting-time-window-in-min]
  (Timer. (SlidingTimeWindowReservoir. reporting-time-window-in-min TimeUnit/MINUTES)))

(defn- register-timer [timer mname]
  (.register ^MetricRegistry mcore/default-registry mname timer))

(defn- new-stored-and-registered-timer [timers reporting-time-window-in-min mname]
  (let [t (sliding-window-timer reporting-time-window-in-min)]
    (register-timer t mname)
    (swap! timers assoc mname t)
    t))

(defn- timer-for-id [{:keys [timers reporting-time-window-in-min]} t-id]
  (let [mname (mcore/metric-name t-id)]
    (or (get @timers mname)
        (new-stored-and-registered-timer timers reporting-time-window-in-min mname))))

(defn- new-handler-name [registered-handlers]
  (str "tesla-handler-" (count registered-handlers)))

(defn- handler-execution-result [request {handler-fn :handler :as handler-map}]
  (when-let [response (handler-fn request)]
    (assoc handler-map :response response)))

(defn- first-handler-result [handlers request]
  (some (partial handler-execution-result request) handlers))

(defn- report-request-timings! [timer-id self time-taken]
  (-> (timer-for-id self timer-id)
      (.update time-taken TimeUnit/MILLISECONDS)))

(defn- time-taken [start-time]
  (- (System/currentTimeMillis) start-time))

(defn- timer-path-fn-result [timer-path-fn request response]
  (try
    (some->> (timer-path-fn request response)
             (map str))
    (catch Exception e
      (log/error e "error executing the timer-path-fn in tesla-microservice: handler: " (.getMessage e)))))

(defn- single-handler-fn [{:keys [registered-handlers] :as self}]
  (fn [request]
    (let [start-time (System/currentTimeMillis)]
      (when-let [{:keys [response timer-path-fn]} (first-handler-result @registered-handlers request)]
        (some-> timer-path-fn
                (timer-path-fn-result request response)
                (report-request-timings! self (time-taken start-time)))
        response))))

(def without-leading-and-trailing-slash #"/?(.*[^/])/?")

(defn- trimmed-uri-path [uri]
  (let [path (.getPath (URI. uri))]
    (second (re-matches without-leading-and-trailing-slash path))))

(defn- extract-uri-resources [uri-resource-fn {:keys [uri]}]
  (uri-resource-fn
    (if-let [splittable (trimmed-uri-path uri)]
      (string/split splittable #"/")
      [])))

(defn- request-based-timer-id [reporting-base-path uri-resource-fn request response]
  (concat
    reporting-base-path
    (extract-uri-resources uri-resource-fn request)
    [(str (:status response))]))

(defn- lookup-uri-resource-fn [uri-resource-fn-or-keyword]
  (cond (keyword? uri-resource-fn-or-keyword)
        (uri-resource-fn-or-keyword {:all-but-last-resource butlast
                                     :all-resources         identity})
        :default uri-resource-fn-or-keyword))

(defn- default-timer-path-fn [reporting-base-path uri-resource-fn-or-keyword]
  (fn [request response]
    (request-based-timer-id
      reporting-base-path
      (lookup-uri-resource-fn uri-resource-fn-or-keyword)
      request response)))

(defn register-timed-handler [self new-handler-fn & {:keys [uri-resource-fn-or-keyword timer-path-fn]
                                                     :or   {uri-resource-fn-or-keyword :all-resources}}]
  (let [{:keys [registered-handlers reporting-base-path]} self
        handler-name (new-handler-name @registered-handlers)]
    (swap! registered-handlers
           #(conj % {:handler-name  handler-name
                     :timer-path-fn (or timer-path-fn
                                        (default-timer-path-fn reporting-base-path uri-resource-fn-or-keyword))
                     :handler       new-handler-fn}))))

(defn register-handler [{:keys [registered-handlers]} new-handler-fn]
  (let [handler-name (new-handler-name @registered-handlers)]
    (swap! registered-handlers #(conj % {:handler-name handler-name
                                         :handler      new-handler-fn}))))

(defn handler [self]
  (single-handler-fn self))

(defrecord Handler [config]
  component/Lifecycle
  (start [self]
    (log/info "-> starting Handler")
    (assoc self
      :reporting-base-path (get-in config [:config :handler :reporting-base-path] ["serving" "requests"])
      :reporting-time-window-in-min (get-in config [:config :handler :reporting-time-window-in-min] 1)
      :timers (atom {})
      :registered-handlers (atom [])))

  (stop [self]
    (log/info "<- stopping Handler")
    self))

(defn new-handler []
  (map->Handler {}))
