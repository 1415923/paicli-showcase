UserLookup.find throws when the id is absent. Fix it so a missing user returns Optional.empty without changing the public return type or tests.
