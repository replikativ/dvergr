(ns dvergr.core
  "Public API facade for dvergr.

   Re-exports the most-used vars from the underlying namespaces so a
   single require gives you the working vocabulary:

     (require '[dvergr.core :as d])

     (def room (d/room :my-room))
     (d/join room (d/llm-agent {:id :coder :spec spec}))
     (d/post! room (d/message :you :coder \"hello\"))

   For deeper APIs reach into the underlying namespaces directly:

     dvergr.discourse                — Room + participant + ask/fan-out/race
     dvergr.discourse.llm            — llm-agent constructor
     dvergr.discourse.generation     — GenerationHandle + adapters
     dvergr.bus                      — pub/sub routing kernel
     dvergr.participant.context      — ParticipantContext
     dvergr.personas                 — researcher, coder, reviewer
     dvergr.proposals                — propose! / accept-proposal! / reject-proposal!"
  (:require [dvergr.discourse]
            [dvergr.discourse.llm]
            [dvergr.discourse.generation]
            [dvergr.bus]
            [dvergr.participant.context]
            [dvergr.personas]
            [dvergr.proposals]))

;; ============================================================================
;; Discourse — substrate + algebra
;; ============================================================================

(def room                    @#'dvergr.discourse/room)
(def participant             @#'dvergr.discourse/participant)
(def message                 @#'dvergr.discourse/message)
(def join                    @#'dvergr.discourse/join)
(def leave                   @#'dvergr.discourse/leave)
(def post!                   @#'dvergr.discourse/post!)
(def post-batch!             @#'dvergr.discourse/post-batch!)
(def log                     @#'dvergr.discourse/log)
(def ask                     @#'dvergr.discourse/ask)
(def fan-out                 @#'dvergr.discourse/fan-out)
(def race                    @#'dvergr.discourse/race)
(def quorum                  @#'dvergr.discourse/quorum)
(def pipeline                @#'dvergr.discourse/pipeline)
(def fork-room               @#'dvergr.discourse/fork-room)
(def merge-room              @#'dvergr.discourse/merge-room)
(def discard                 @#'dvergr.discourse/discard)
(def hire                    @#'dvergr.discourse/hire)
(def subscribe!              @#'dvergr.discourse/subscribe!)
(def unsubscribe!            @#'dvergr.discourse/unsubscribe!)
(def scripted                @#'dvergr.discourse/scripted)
(def echo                    @#'dvergr.discourse/echo)
(def with-cadence            @#'dvergr.discourse/with-cadence)
(def with-sources            @#'dvergr.discourse/with-sources)

;; ============================================================================
;; LLM-backed participant
;; ============================================================================

(def llm-agent               @#'dvergr.discourse.llm/llm-agent)

;; ============================================================================
;; Personas (pre-built llm-agent factories)
;; ============================================================================

(def researcher              @#'dvergr.personas/researcher)
(def coder                   @#'dvergr.personas/coder)
(def reviewer                @#'dvergr.personas/reviewer)
(def persona-from-prompt     @#'dvergr.personas/from-prompt)

;; ============================================================================
;; Proposals (fork + worker + accept/reject)
;; ============================================================================

(def propose!                @#'dvergr.proposals/propose!)
(def accept-proposal!        @#'dvergr.proposals/accept-proposal!)
(def reject-proposal!        @#'dvergr.proposals/reject-proposal!)

;; ============================================================================
;; ParticipantContext (unified memory + budget for any participant)
;; ============================================================================

(def participant-context     @#'dvergr.participant.context/->ParticipantContext)
(def create-human-context    @#'dvergr.participant.context/create-human-context)
(def create-llm-context      @#'dvergr.participant.context/create-llm-context)
(def from-chat-context       @#'dvergr.participant.context/from-chat-context)

;; ============================================================================
;; Bus (compositional pub/sub kernel — usually accessed via Room, but useful
;; for tag-routed handlers that don't need a full Participant)
;; ============================================================================

(def create-bus              @#'dvergr.bus/create-bus)
(def bus-post!               @#'dvergr.bus/post!)
(def bus-subscribe!          @#'dvergr.bus/subscribe!)
(def bus-unsubscribe!        @#'dvergr.bus/unsubscribe!)
(def bus-log                 @#'dvergr.bus/log)
(def default-buffers         @#'dvergr.bus/*default-buffers*)

;; ============================================================================
;; GenerationHandle (F-side primitive for swapping the decider shape)
;; ============================================================================

(def sync-handle             @#'dvergr.discourse.generation/sync-handle)
(def future-handle           @#'dvergr.discourse.generation/future-handle)
(def external-handle         @#'dvergr.discourse.generation/external-handle)
(def streaming-handle        @#'dvergr.discourse.generation/streaming-handle)
(def race-handles            @#'dvergr.discourse.generation/race-handles)
(def fallback-handle         @#'dvergr.discourse.generation/fallback-handle)
