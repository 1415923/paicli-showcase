Refactor the duplicated trim/lowercase normalization into a new TextNormalizer and make both CustomerMatcher and EmailIndex use it. Preserve behavior and do not edit tests.
