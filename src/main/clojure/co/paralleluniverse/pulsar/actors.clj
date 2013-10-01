; Pulsar: lightweight threads and Erlang-like actors for Clojure.
; Copyright (C) 2013, Parallel Universe Software Co. All rights reserved.
;
; This program and the accompanying materials are dual-licensed under
; either the terms of the Eclipse Public License v1.0 as published by
; the Eclipse Foundation
;
;   or (per the licensee's choosing)
;
; under the terms of the GNU Lesser General Public License version 3.0
; as published by the Free Software Foundation.

(ns co.paralleluniverse.pulsar.actors
  "Defines actors and behaviors like gen-server and supervisor"
  (:import [java.util.concurrent TimeUnit ExecutionException TimeoutException]
           [co.paralleluniverse.fibers FiberScheduler]
           [co.paralleluniverse.strands Strand]
           [co.paralleluniverse.strands.channels Channel SendPort]
           [co.paralleluniverse.actors Actor ActorRef ActorRegistry PulsarActor ActorBuilder MailboxConfig
            ActorUtil LocalActorUtil
            LifecycleListener ShutdownMessage]
           [co.paralleluniverse.pulsar ClojureHelper]
           [co.paralleluniverse.actors.behaviors GenBehavior GenBehaviorActor Initializer
            GenServer GenServerActor
            GenEvent GenEventActor EventHandler
            Supervisor Supervisor$ChildSpec Supervisor$ChildMode SupervisorActor SupervisorActor$RestartStrategy]
           ; for types:
           [clojure.lang Keyword IObj IFn IMeta IDeref ISeq IPersistentCollection IPersistentVector IPersistentMap])
  (:require [co.paralleluniverse.pulsar.core :refer :all]
            [co.paralleluniverse.pulsar.interop :refer :all]
            [clojure.string :as str]
            [clojure.core.match :refer [match]]
            [clojure.core.typed :refer [ann def-alias Option AnyInteger]]))

;; ## Private util functions
;; These are internal functions aided to assist other functions in handling variadic arguments and the like.

;; from core.clj:
(defmacro ^{:private true} assert-args
  [& pairs]
  `(do (when-not ~(first pairs)
         (throw (IllegalArgumentException.
                  (str (first ~'&form) " requires " ~(second pairs) " in " ~'*ns* ":" (:line (meta ~'&form))))))
     ~(let [more (nnext pairs)]
        (when more
          (list* `assert-args more)))))

(ann nth-from-last (All [x y]
                        (Fn [(IPersistentCollection x) Long -> x]
                            [(IPersistentCollection x) Long y -> (U x y)])))
(defn- nth-from-last
  ([coll index]
   (nth coll (- (dec (count coll)) index)))
  ([coll index not-found]
   (nth coll (- (dec (count coll)) index) not-found)))

(ann split-at-from-last (All [x]
                             [Long (IPersistentCollection x) -> (Vector* (IPersistentCollection x) (IPersistentCollection x))]))
(defn- split-at-from-last
  [index coll]
  (split-at (- (dec (count coll)) index) coll))

;; ## Actors

(defmacro actor
  "Creates a new actor."
  {:arglists '([bindings & body])}
  [bs & body]
  (assert-args
    (vector? bs) "a vector for its binding"
    (even? (count bs)) "an even number of forms in binding vector")
  `(suspendable!
     ~(if (seq bs)
        ; actor with state fields
        (let [type (gensym "actor")
              fs (vec (take-nth 2 bs)) ; field names
              ivs (take-nth 2 (next bs))] ; initial values
          (eval ; this runs at compile time!
            (let [fs (mapv #(merge-meta % {:unsynchronized-mutable true}) fs)]
              `(deftype ~type ~fs
                 clojure.lang.IFn
                 (invoke [this#] ~@body)
                 (applyTo [this# args#] (clojure.lang.AFn/applyToHelper this# args#)))))
          `(new ~type ~@ivs))
        ; regular actor
        `(fn [] ~@body))))

(defmacro defactor
  "Defines a new actor template."
  {:arglists '([name doc-string? attr-map? [params*] body])}
  [n & decl]
  (let [decl1 decl
        decl1 (if (string? (first decl1)) (next decl1) decl1) ; strip docstring
        decl1 (if (map? (first decl1)) (next decl1) decl1)    ; strip meta
        fs (first decl1)]
    (assert-args
      (vector? fs) "a vector for its binding")
    (if (seq fs)
      ; actor with state fields
      (let [fs1 (mapv #(merge-meta % {:unsynchronized-mutable true}) fs)
            body (next decl1)
            type (symbol (str (name n) "_type"))
            arity (count fs)
            args  (repeatedly arity gensym)]
        `(do
           (deftype ~type ~fs1
             clojure.lang.IFn
             (invoke [this#] ~@body)
             (applyTo [this# args#] (clojure.lang.AFn/applyToHelper this# args#)))
           (suspendable! ~type)
           (defn ~n [~@args]
             ((new ~type ~@args)))
           (let [sn# (suspendable! ~n)]
             (def ~n sn#)
             sn#)))
      ; regular actor
      `(do
         (defn ~n ~@decl)
         (let [sn# (suspendable! ~n)]
             (def ~n sn#)
             sn#)))))

(defmacro ->MailboxConfig 
  [size overflow-policy]
  `(co.paralleluniverse.actors.MailboxConfig. (int ~size) (keyword->enum co.paralleluniverse.strands.channels.Channels$OverflowPolicy ~overflow-policy)))

(defmacro spawn
  "Creates and starts a new actor running in its own, newly-spawned fiber.
  
  f - the actor function.
  args - (optional) arguments to for the function.
  
  Options:
  * `:name` - The actor's name (that's also given to the fiber running the actor). The name can be a string
              or a keyword, in which case it's identical to the keyword's name (i.e. a name of `\"foo\"` is the same as `:foo`).
  * `:mailbox-size` - The number of messages that can wait in the mailbox, 
                      or -1 (the default) for an unbounded mailbox.
  * `:overflow-policy` - What to do if a bounded mailbox overflows. Can be on of:
     - `:throw` - an exception will be thrown *into the receiving actor*
     - `:drop`  -  the message will be silently discarded 
     - `:block` - the sender will block until there's room in the mailbox.  
  * `:trap` - If set to `true`, linked actors' death will send an exit message rather than throw an exception.
  * `:lifecycle-handle` - A function that will be called to handle special messages sent to the actor. 
                          If set to `nil` (the default), the default handler is used, which is what you 
                          want in all circumstances, except for some actors that are meant to do some 
                          special tricks.
  * `:scheduler` - The `FiberScheduler` in which the fiber will run.
                 If `:fj-pool` is not specified, then the pool used will be either the pool of the fiber calling 
                 `spawn-fiber`, or, if `spawn-fiber` is not called from within a fiber, a default pool.
  * `:stack-size` - The initial fiber stack size."
  {:arglists '([:name? :mailbox-size? :overflow-policy? :lifecycle-handler? :stack-size? :pool? f & args])}
  [& args]
  (let [[{:keys [^String name ^Boolean trap ^Integer mailbox-size overflow-policy ^IFn lifecycle-handler ^Integer stack-size ^FiberScheduler scheduler], :or {trap false mailbox-size -1 stack-size -1}} body] (kps-args args)]
    `(let [b#     (first ~body) ; eval body
           nme#   (when ~name (clojure.core/name ~name))
           f#     (when (not (instance? Actor b#))
                    (suspendable! ~(if (== (count body) 1) (first body) `(fn [] (apply ~(first body) (list ~@(rest body)))))))
           ^Actor actor# (if (instance? Actor b#)
                                b#
                                (co.paralleluniverse.actors.PulsarActor. nme# ~trap (->MailboxConfig ~mailbox-size ~overflow-policy) ~lifecycle-handler f#))
           fiber# (co.paralleluniverse.fibers.Fiber. nme# (get-scheduler ~scheduler) (int ~stack-size) actor#)]
       (.start fiber#)
       (.ref actor#))))

(defmacro spawn-link
  "Creates and starts, as by `spawn`, a new actor, and links it to @self.
  
  See: `link!`"
  {:arglists '([:name? :mailbox-size? :overflow-policy? :lifecycle-handler? :stack-size? :pool? f & args])}
  [& args]
  `(let [actor# ~(list `spawn ~@args)]
     (link! actor#)
     actor#))

(defn done?
  "Tests whether or not an actor has terminated."
  [^ActorRef a]
  (LocalActorUtil/isDone a))

;(ann-protocol IUnifyWithLVar
;              unify-with-lvar [Term LVar ISubstitutions -> (U ISubstitutions Fail)])

(def self
  "@self is the currently running actor"
  (reify
    clojure.lang.IDeref
    (deref [_] (ActorRef/self))))

(ann state (IDeref Any))
(def state
  "@state is the state of the currently running actor.
  The state can be set with `set-state!`"
  (reify
    clojure.lang.IDeref
    (deref [_] (PulsarActor/selfGetState))))

(ann set-state! (All [x] [x -> x]))
(defn set-state!
  "Sets the state of the currently running actor.
  The state can be read with `@state`."
  [x]
  (PulsarActor/selfSetState x))

(ann mailbox (IDeref Channel))
(def mailbox
  "@mailbox is the mailbox channel of the currently running actor"
  (reify
    clojure.lang.IDeref
    (deref [_] (PulsarActor/selfMailbox))))


(ann get-actor [Any -> Actor])
(defn ^ActorRef get-actor
  "If the argument is an actor -- returns it. If not, looks up a registered 
  actor with the argument as its name.
  
  The name can be a string or a keyword, in which case it's identical to the keyword's name 
  (i.e. a name of `\"foo\"` is the same as `:foo`)."
  [a]
  (when a
    (if (instance? ActorRef a)
      a
      (ActorRegistry/getActor (name a)))))

(ann trap! [-> nil])
(defn trap!
  "Sets the current actor to trap lifecycle events (like a dead linked actor) 
  and turn them into exit messages.
  Same as adding `:trap true` to `spawn`."
  []
  (.setTrap ^PulsarActor @self true))


(ann link! (Fn [ActorRef -> ActorRef]
               [ActorRef ActorRef -> ActorRef]))
(defn link!
  "Links two actors. If only one actor is specified, links the current actor with the
  specified actor.
  
  A link is symmetrical. When two actors are linked, when one of them dies, the other throws 
  a `co.paralleluniverse.actors.LifecycleException` exception which, unless caught, kills it 
  as well.
  If `:trap true` was added to the actor's `spawn` call, or if `(trap!)` has been called by
  the actor, rather than an exception being thrown, an exit message is sent to the actor.
  The message is of the same structure as the one sent as a result of `watch!` except that
  the watch element is `nil`.
  
  See: `unlink!`, `watch!`"
  ([actor2]
   (.link ^Actor (Actor/currentActor) actor2))
  ([actor1 actor2]
   (LocalActorUtil/link (get-actor actor1) (get-actor actor2))))

(ann unlink! (Fn [Actor -> Actor]
                 [Actor Actor -> Actor]))
(defn unlink!
  "Unlinks two actors. If only one actor is specified, unlinks the current actor from the
  specified actor.
  
  See: `link!`"
  ([actor2]
   (.unlink ^Actor (Actor/currentActor) actor2))
  ([actor1 actor2]
   (LocalActorUtil/unlink (get-actor actor1) (get-actor actor2))))

(ann watch! (Fn [Actor Actor -> LifecycleListener]
                 [Actor -> LifecycleListener]))
(defn watch!
  "Makes the current actor watch another actor. Returns a watch object which is then
  used in all relevant exit messages, and should also be used when calling `unwatch!`.
  
  Unlike links, watches are assymetrical. If a the watched actor dies, the watching 
  actor (the actor calling this function), receives an exit message. 
  
  The message is a vector of 4 elements, of the following structure:
  
  [:exit w actor cause]
  
  `w` - the watch object returned from the call to `watch!`, which is responsible for the
        message being sent. If the `watch!` function is called more than once to watch
        the same actor, an exit message will be received several times, each one corresponding
        to an invocation of `watch!`, and each with a different value for `w`.
  `actor` - the dead (watched) actor.
  `cause` - the dead actor's cause of death: `nil` for a normal termination; a Throwable for
            an exceptional termination.

  See: `unwatch!`, `link!`"
  [actor]
   (.watch (Actor/currentActor) actor))

(ann unwatch! (Fn [Actor Actor LifecycleListener -> nil]
                 [Actor LifecycleListener -> nil]))
(defn unwatch!
  "Makes an actor stop watching another actor"
  ([actor2 monitor]
   (.unwatch ^Actor (Actor/currentActor) actor2 monitor)))

(ann register (Fn [String LocalActor -> LocalActor]
                  [LocalActor -> LocalActor]))
(defn register!
  "Registers an actor in the actor registry.
  The actor is registered by its name, or, if it doesn't have a name, one must be supplied
  to this function. The name can be a string or a keyword, in which case it's identical to the 
  keyword's name (i.e. a name of `\"foo\"` is the same as `:foo`)."
  ([actor-name ^ActorRef actor]
   (LocalActorUtil/register actor (name actor-name)))
  ([actor-or-name]
   (if (instance? ActorRef actor-or-name)
     (LocalActorUtil/register actor-or-name)
     (.register (Actor/currentActor) actor-or-name)))
  ([]
   (.register (Actor/currentActor))))

(defn unregister!
  "Unregisters an actor.
  
  If no argument is supplied, unregisters the current actor."
([x]
 (let [^ActorRef actor x]
   (LocalActorUtil/unregister actor)))
([]
 (.unregister (Actor/currentActor))))

(ann mailbox-of [PulsarActor -> Channel])
(defn ^SendPort mailbox-of
  "Returns the mailbox of the given actor."
  [^ActorRef actor]
  actor)

(ann whereis [Any -> Actor])
(defn ^ActorRef whereis
  "Returns a registered actor by name."
  [actor-name]
  (ActorRegistry/getActor (name actor-name)))

(ann maketag [-> Number])
(defn maketag
  "Returns a random, probably unique, identifier.
  (this is similar to Erlang's makeref)."
  []
  (ActorUtil/randtag))

(ann tagged-tuple? [Any -> Boolean])
(defn tagged-tuple?
  "Tests whether argument x is a vector whose first element is a keyword."
  {:no-doc true}
  [x]
  (and (vector? x) (keyword? (first x))))

(defn clojure->java-msg
  {:no-doc true}
  [x]
  (if (not (tagged-tuple? x))
    x
    (case (first x)
      :shutdown (ShutdownMessage. (second x))
      x)))

(defmacro !
  "Sends a message to an actor.
  This function returns `nil`.
  If the actor's mailbox capacity has been exceeded, this function's behavior
  is determined by the `overflow-policy` set by the receiving actor's `spawn`.
  
  See: `spawn`"
  ([actor message]
   `(co.paralleluniverse.actors.PulsarActor/send (get-actor ~actor) (clojure->java-msg ~message)))
  ([actor arg & args]
   `(co.paralleluniverse.actors.PulsarActor/send (get-actor ~actor) (clojure->java-msg [~arg ~@args]))))

(defmacro !!
  "Sends a message to an actor synchronously.
  This has the exact same semantics as !, but hints to the scheduler that the 
  current actor is about to wait for a response from the message's addressee.
  
  See: `!`"
  ([actor message]
   `(co.paralleluniverse.actors.PulsarActor/sendSync (get-actor ~actor) (clojure->java-msg ~message)))
  ([actor arg & args]
   `(co.paralleluniverse.actors.PulsarActor/sendSync (get-actor ~actor) (clojure->java-msg [~arg ~@args]))))

(ann receive-timed [AnyInteger -> (Option Any)])
(defsfn receive-timed
  "Waits (and returns) for a message for up to timeout ms. If time elapses -- returns nil."
  [^Integer timeout]
  (co.paralleluniverse.actors.PulsarActor/selfReceive timeout))

;; For examples of this macro's expansions, try:
;; (pprint (macroexpand-1 '(receive)))
;; (pprint (macroexpand-1 '(receive [:a] :hi :else :bye)))
;; (pprint (macroexpand-1 '(receive [msg] [:a] :hi :else :bye)))
;; (pprint (macroexpand-1 '(receive [msg #(* % %)] [:a] :hi :else :bye)))
;;
;; (pprint (macroexpand-1 '(receive :else m :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [m] :else m :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [:a] :hi :else :bye :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [msg] [:a] :hi :else :bye :after 30 :foo)))
;; (pprint (macroexpand-1 '(receive [msg #(* % %)] [:a] :hi :else :bye :after 30 :foo)))
;;
;; (pprint (macroexpand-1 '(receive [:a x] [:hi x] [:b x] [:bye x])))
;; (pprint (macroexpand-1 '(receive [:a x] [:hi x] [:b x] [:bye x] :after 30 :foo)))

(defmacro receive
  "Receives a message in the current actor and processes it.
  
  Receive performs pattern matching (with free var binding) on the message.
  Example:
    (let [actor (spawn
                 #(receive
                     :abc \"yes!\"
                     [:why? answer] answer
                     :else \"oy\"))]
       (! actor [:why? \"because!\"])
       (join actor)) ; => \"because!\"
  
  `receive` performs a *selective receive*. If the next message in the mailbox does
  not match any of the patterns (and an `:else` clause is not present), it is skipped, 
  and the next message will be attempted.
  `receive` will block until a matching message arrives, and will return the value of
  the matching clause.
  
  Skipped messages are not discarded, but are left in the mailbox. Every call to `receive` 
  will attempt to match any message in the mailbox, starting with the oldest. 
  (Skipped messages migh accumulate in the mailbox if not matched, so it's good practice
  to at least occasionally call a `receive` that has an `:else` clause.)
  
  If the first element of the `receive` expression is a vector, it is used for binding:
  The vector's first element is the name assigned to the entire message, and the second,
  if it exists, is a transformation function, of one argument, that will be applied to 
  the message before binding and before pattern-matching:

     (receive [m transform]
       [:foo val] (println \"got foo:\" val)
       :else      (println \"got\" m))

   Now `m` – and the value we're matching – is the the transformed value.

  A timeout in milliseconds, may be specified in an `:after` clause, which must appear last:

    (receive [m transform]
       [:foo val] (println \"got foo:\" val)
       :else      (println \"got\" m)
       :after 30  (println \"nothing...\"))"
  {:arglists '([]
               [patterns* <:after ms action>?]
               [[binding transformation?] patterns* <:after ms action>?])}
  ([]
   `(co.paralleluniverse.actors.PulsarActor/selfReceive))
  ([& body]
   (assert-args
     (or (even? (count body)) (vector? (first body))) "a vector for its binding")
   (let [[body after-clause] (if (= :after (nth-from-last body 2 nil)) (split-at-from-last 2 body) [body nil])
         odd-forms   (odd? (count body))
         bind-clause (if odd-forms (first body) nil)
         transform   (second bind-clause)
         body        (if odd-forms (next body) body)
         m           (if bind-clause (first bind-clause) (gensym "m"))
         timeout     (gensym "timeout")
         iter        (gensym "iter")]
     (if (seq (filter #(= % :else) (take-nth 2 body)))
       ; if we have an :else then every message is processed and our job is easy
       `(let ~(into [] (concat
                         (if after-clause `[~timeout ~(second after-clause)] [])
                         `[~m ~(concat `(co.paralleluniverse.actors.PulsarActor/selfReceive) (if after-clause `(~timeout) ()))]
                         (if transform `[~m (~transform ~m)] [])))
          ~@(surround-with (when after-clause `(if (nil? ~m) ~(nth after-clause 2)))
                           `(match ~m ~@body)))
       ; if we don't, well, we have our work cut out for us
       (let [pbody   (partition 2 body)
             mailbox (gensym "mailbox") n (gensym "n") m2 (gensym "m2") mtc (gensym "mtc") exp (gensym "exp")] ; symbols
         `(let [[~mtc ~m]
                (let ~(into [] (concat `[^co.paralleluniverse.actors.PulsarActor ~mailbox (co.paralleluniverse.actors.PulsarActor/currentActor)]
                                       (if after-clause `[~timeout ~(second after-clause)
                                                          ~exp (if (pos? ~timeout) (long (+ (long (System/nanoTime)) (long (* 1000000 ~timeout)))) 0)] [])))
                  (.maybeSetCurrentStrandAsOwner ~mailbox)
                  (loop [prev# nil ~iter 0]
                    (.lock ~mailbox)
                    (let [~n (.succ ~mailbox prev#)]
                      ~(let [quick-match (concat ; ((pat1 act1) (pat2 act2)...) => (pat1 (do (.processed mailbox# n#) 0) pat2 (do (del mailbox# n#) 1)... :else -1)
                                           (mapcat #(list (first %1) `(do (.processed ~mailbox ~n) ~%2)) pbody (range)); for each match pattern, call processed and return an ordinal
                                           `(:else (do (.skipped ~mailbox ~n) -1)))]
                         `(if ~n
                            (do
                              (.unlock ~mailbox)
                              (let [m1# (.value ~mailbox ~n)]
                                (when (and (not (.isTrap ~mailbox)) (instance? co.paralleluniverse.actors.LifecycleMessage m1#))
                                  (.handleLifecycleMessage ~mailbox m1#))
                                (let [~m2 (co.paralleluniverse.actors.PulsarActor/convert m1#)
                                      ~m ~(if transform `(~transform ~m2) `~m2)
                                      act# (int (match ~m ~@quick-match))]
                                  (if (>= act# 0)
                                    [act# ~m]     ; we've got a match!
                                    (recur ~n (inc ~iter)))))) ; no match. try the next
                            ; ~n == nil
                            ~(if after-clause
                               `(when-not (== ~timeout 0)
                                  (do ; timeout != 0 and ~n == nil
                                    (try
                                      (.await ~mailbox ~iter (- ~exp (long (System/nanoTime))) java.util.concurrent.TimeUnit/NANOSECONDS)
                                      (finally
                                        (.unlock ~mailbox)))
                                    (when-not (> (long (System/nanoTime)) ~exp)
                                      (recur ~n (inc ~iter)))))
                               `(do
                                  (try
                                    (.await ~mailbox ~iter)
                                    (finally
                                      (.unlock ~mailbox)))
                                  (recur ~n (inc ~iter)))))))))]
            ~@(surround-with (when after-clause `(if (nil? ~mtc) ~(nth after-clause 2)))
                             ; now, mtc# is the number of the matching clause and m# is the message.
                             ; but the patterns might have wildcards so we need to match again (for the bindings)
                             `(case (int ~mtc) ~@(mapcat #(list %2 `(match [~m] [~(first %1)] ~(second %1))) pbody (range))))))))))
;`(match [~mtc ~m] ~@(mapcat #(list [%2 (first %1)] (second %1)) pbody (range))))))))))

(defn shutdown!
  "Asks a gen-server or a supervisor to shut down"
  ([^GenBehavior gs]
   (.shutdown gs))
  ([]
   (.shutdown ^GenBehavior @self)))

(defn ^Initializer ->Initializer 
  ([init terminate]
   (let [init      (when init (suspendable! init))
         terminate (when terminate (suspendable! terminate))]
     (reify
       Initializer
       (^void init [this]
              (when init (init)))
       (^void terminate  [this ^Throwable cause]
              (when terminate (terminate cause))))))
  ([init]
   (->Initializer init nil)))

(defmacro request!
  [actor & message]
  `(join (spawn (fn [] 
                  (! actor ~@message)
                  (receive)))))

(defmacro request-timed!
  [timeout actor & message]
  `(join (spawn (fn [] 
                  (! actor ~@message)
                  (receive-timed timeout)))))

(defn capitalize [s]
  {:no-doc true}
  (str/capitalize s))

(defmacro log
  [level message & args]
  `(let [^org.slf4j.Logger log# (.log ^GenBehaviorActor (Actor/currentActor))]
     (if (. log# ~(symbol (str "is" (capitalize (name level)) "Enabled")))
       (. log# ~(symbol (name level)) ~message (to-array (vector ~@args))))))

;; ## gen-server

(defprotocol Server
  (init [this])
  (handle-call [this ^Actor from id message])
  (handle-cast [this ^Actor from id message])
  (handle-info [this message])
  (handle-timeout [this])
  (terminate [this ^Throwable cause]))

(suspendable! co.paralleluniverse.pulsar.actors.Server)

(defn ^co.paralleluniverse.actors.behaviors.Server Server->java
  {:no-doc true}
  [server]
  (suspendable! server [co.paralleluniverse.pulsar.actors.Server])
  (reify
    co.paralleluniverse.actors.behaviors.Server
    (^void init [this]
           (init server))
    (handleCall [this ^ActorRef from id message]
                (handle-call server from id message))
    (^void handleCast [this ^ActorRef from id message]
           (handle-cast server from id message))
    (^void handleInfo [this message]
           (handle-info server message))
    (^void handleTimeout [this]
           (handle-timeout server))
    (^void terminate  [this ^Throwable cause]
           (terminate server cause))))

(defmacro gen-server
  "Creates (but doesn't start) a new gen-server"
  {:arglists '([:name? :timeout? :mailbox-size? :overflow-policy? server & args])}
  [& args]
  (let [[{:keys [^String name ^Integer timeout ^Integer mailbox-size overflow-policy], :or {timeout -1 mailbox-size -1}} body] (kps-args args)]
    `(co.paralleluniverse.actors.behaviors.GenServerActor. ~name
                                                           ^co.paralleluniverse.actors.behaviors.Server (Server->java ~(first body))
                                                           (long ~timeout) java.util.concurrent.TimeUnit/MILLISECONDS
                                                           nil (->MailboxConfig ~mailbox-size ~overflow-policy))))

(defn call!
  "Makes a synchronous call to a gen-server and returns the response"
  ([^GenServer gs m]
   (unwrap-exception
     (.call gs m)))
  ([^GenServer gs m & args]
   (unwrap-exception
     (.call gs (vec (cons m args))))))

(defn call-timed!
  "Makes a synchronous call to a gen-server and returns the response"
  ([^GenServer gs timeout unit m]
   (unwrap-exception
     (.call gs m (long timeout) (->timeunit unit))))
  ([^GenServer gs timeout unit m & args]
   (unwrap-exception
     (.call gs (vec (cons m args)) (long timeout) (->timeunit unit)))))

(defn cast!
  "Makes an asynchronous call to a gen-server"
  ([^GenServer gs m]
   (.cast gs m))
  ([^GenServer gs m & args]
   (.cast gs (vec (cons m args)))))

(defn set-timeout!
  "Sets the timeout for the current gen-server"
  [timeout unit]
  (.setTimeout (GenServerActor/currentGenServer) timeout (->timeunit unit)))

(defn reply!
  "Replies to a message sent to the current gen-server"
  [^Actor to id res]
  (.reply (GenServerActor/currentGenServer) to id res))

(defn reply-error!
  "Replies with an error to a message sent to the current gen-server"
  [^Actor to id ^Throwable error]
  (.replyError (GenServerActor/currentGenServer) to id error))

;; ## gen-event

(defn gen-event
  "Creates (but doesn't start) a new gen-event"
  {:arglists '([:name? :timeout? :mailbox-size? :overflow-policy? server & args])}
  [& args]
  (let [[{:keys [^String name ^Integer mailbox-size overflow-policy], :or {mailbox-size -1}} body] (kps-args args)]
    (GenEventActor. name
                    (->Initializer (first body) (second body))
                    nil (->MailboxConfig mailbox-size overflow-policy))))

(deftype PulsarEventHandler
  [handler]
  EventHandler
  (handleEvent [this event]
               (handler event))
  Object
  (equals [this other]
          (and (instance? PulsarEventHandler other) (= handler (.handler ^PulsarEventHandler other)))))

(defn notify!
  [^GenEvent ge event]
  (.notify ge event))

(defn add-handler!
  [^GenEvent ge handler]
  (.addHandler ge (->PulsarEventHandler (suspendable! handler))))

(defn remove-handler!
  [^GenEvent ge handler]
  (.removeHandler ge (->PulsarEventHandler (suspendable! handler))))

;; ## supervisor

(defn- ^ActorBuilder actor-builder
  [f]
  (reify ActorBuilder
    (build [this]
           (f))))

(defn- ^Actor create-actor
  [& args]
  (let [[{:keys [^String name ^Boolean trap ^Integer mailbox-size overflow-policy ^IFn lifecycle-handler ^Integer stack-size], :or {trap false mailbox-size -1 stack-size -1}} body] (kps-args args)
        f  (when (not (instance? Actor (first body)))
             (suspendable! (if (== (count body) 1)
                             (first body)
                             (fn [] (apply (first body) (rest body))))))]
    (if (nil? f)
      (first body)
      (PulsarActor. name trap (->MailboxConfig mailbox-size overflow-policy) lifecycle-handler f))))

(defn- child-spec
  [id mode max-restarts duration unit shutdown-deadline-millis & args]
  (Supervisor$ChildSpec. id
                         (keyword->enum Supervisor$ChildMode mode)
                         (int max-restarts)
                         (long duration) (->timeunit unit)
                         (long shutdown-deadline-millis)
                         (if (instance? ActorBuilder (first args))
                           (first args)
                           (actor-builder #(apply create-actor args)))))

(defsfn add-child!
  "Adds an actor to a supervisor"
  [^Supervisor supervisor id mode max-restarts duration unit shutdown-deadline-millis & args]
  (.addChild supervisor (apply-variadic child-spec id mode max-restarts duration unit shutdown-deadline-millis args)))

(defsfn remove-child!
  "Removes an actor from a supervisor"
  [^Supervisor supervisor id]
  (.removeChild supervisor id false))

(defn remove-and-terminate-child!
  "Removes an actor from a supervisor and terminates the actor"
  [^Supervisor supervisor id]
  (.removeChild supervisor id true))

(defn ^ActorRef get-child
  "Returns a supervisor's child by id"
  [^Supervisor sup id]
  (.getChild sup id))

(defn supervisor
  "Creates (but doesn't start) a new supervisor"
  ([^String name restart-strategy init]
   (SupervisorActor. nil name nil
                     ^SupervisorActor$RestartStrategy (keyword->enum SupervisorActor$RestartStrategy restart-strategy)
                     (->Initializer
                       (fn [] (doseq [child (seq ((suspendable! init)))]
                                (apply add-child! (cons @self child)))))))
  ([restart-strategy init]
   (supervisor nil restart-strategy init)))


