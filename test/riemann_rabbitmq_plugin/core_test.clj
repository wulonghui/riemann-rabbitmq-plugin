(ns riemann-rabbitmq-plugin.core-test
  (:use midje.sweet
        [midje.util :only [expose-testables]])
  (:require [riemann-rabbitmq-plugin.core :refer :all]
            [riemann.service    :as service]
            [riemann.core       :as core]
            [riemann.logging    :as logging]
            [langohr.core       :as rmq]
            [langohr.channel    :as lch]
            [langohr.queue      :as lq]
            [langohr.consumers  :as lc]
            [langohr.basic      :as lb])
  (:import [org.apache.log4j Level]))

(expose-testables riemann-rabbitmq-plugin.core)
(logging/init :console true)
(logging/set-level Level/DEBUG)

(comment
"Unfortunately, midje currently has issues with primitive type hints -
see https://groups.google.com/forum/#!topic/midje/xPN-eO_0poI or https://github.com/marick/Midje/issues/295 for more details.

We can work around this using `with-redefs` but that means we have no way to assert (withing midje) that calls to the stubbed function were made.
So, TODO: refactor the code to make it easier for testing or move to core.testing instead of midje"
)


(facts "about `AMQPInput` record"
       (let [prefetch-count (long (* (rand) 10000))
             bindings [{:queue "" :bind-to {..exchange.. [..binding-key..]}}]
             amqp-input (->AMQPInput {:connection-opts ..connection-opts.. :bindings bindings :prefetch-count prefetch-count} (atom nil) (atom nil))]
         (fact "`start!` binds multiple queues with multiple bindings"
               (with-redefs [lb/qos (fn [ch ^long prefetch-count] nil)]
                 (service/start! amqp-input) => irrelevant
                 (provided
                  (rmq/connect ..connection-opts..) => ..conn..
                  (lch/open ..conn..) => ..ch..
                  (lq/declare ..ch.. "" {:exclusive true :auto-delete true}) => {:queue ..queue..}
                  (lq/bind ..ch.. ..queue.. ..exchange.. {:routing-key ..binding-key..}) => nil
                  (lc/subscribe ..ch.. ..queue.. anything) => nil)))))

(facts "about `message-handler`"
       (let [ack-called (atom false)
             payload (byte-array (map int "test"))
             delivery-tag (rand-int 100000)]
         (fact "calls core/stream! when event is correctly parsed"
               (with-redefs [lb/ack (fn [ch ^long delivery-tag] (reset! ack-called delivery-tag) nil)]
                 (message-handler --parser-fn-- (atom ..core..) ..ch.. ..props.. payload) => nil
                 (provided
                  ..props.. =contains=> {:delivery-tag delivery-tag}
                  ;(lb/ack ..ch.. ..delivery-tag..) => nil
                  (--parser-fn-- payload) => ..event..
                  (core/stream! ..core.. ..event..) => nil)))
         (fact "when event isn't correctly parsed, don't call `core/stream!` and reject the message"
               (with-redefs [lb/reject (fn [ch ^long delivery-tag requeue] nil)]
                 (message-handler --parser-fn-- (atom ..core..) ..ch.. ..props.. payload) => nil
                 (provided
                   ..props.. =contains=> {:delivery-tag delivery-tag}
                  (--parser-fn-- payload) => nil)))))
