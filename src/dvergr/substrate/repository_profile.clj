(ns dvergr.substrate.repository-profile
  "Trusted embedder policies for repository lifecycle. Persist only a profile
   keyword; executable hooks come from the host on each create/open. No global
   registry, content-resolved callbacks, interpreter or transport surface.")

(defn resolve!
  "Resolve an explicit profile from a HOST-supplied policy map. An unknown
   mandatory profile never falls back to an ordinary or ungoverned repository.
   Nil keeps the existing sandbox initialization behavior. Hosts may optionally
   supply a read-only :legacy-room-ready? registration predicate; see
   doc/repository-profiles.md."
  [profiles profile]
  (when (some? profile)
    (let [hooks (get profiles profile)]
      (when-not (and (keyword? profile) (map? profiles) (map? hooks)
                     (every? #(contains? hooks %) [:initialize! :prepare-open! :abort-create!])
                     (every? #{:initialize! :prepare-open! :abort-create! :legacy-room-ready?} (keys hooks))
                     (every? fn? (vals hooks)))
        (throw (ex-info "Repository profile requires an installed trusted host policy"
                        {:type ::unavailable :profile profile})))
      hooks)))
