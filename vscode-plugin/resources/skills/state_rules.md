# Angular State (NgRx / Akita / NGXS) — Review Rules

Applied automatically when reviewing `*.effects.ts`, `*.reducer.ts`, `*.action.ts`, `*.selector.ts`, `*.store.ts` files.

---

## Actions
- Verify each action is created with `createAction` and typed props — avoid plain string action types
- Flag duplicate action type strings — they cause silent state bugs
- Ensure action names follow the `[Source] Event` convention (e.g., `[Auth API] Login Success`)
- Check that actions carry only the minimum necessary payload

## Reducers
- Verify reducers are pure functions — no side-effects, no API calls, no randomness
- Flag mutable state updates — always produce a new state object (`{ ...state, field: value }` or Immer)
- Check that all action cases in a switch/`on()` have explicit return values
- Ensure the initial state is typed and includes sensible defaults

## Effects
- Flag effects that dispatch another action unconditionally in a loop (potential infinite dispatch)
- Verify error handling uses `catchError` inside the `pipe` of the inner observable, not the outer stream (to keep the effect alive)
- Check that effects use `switchMap` for cancellable operations (search), `concatMap` for sequential, `mergeMap` for parallel
- Ensure effects that make HTTP calls dispatch both a success and a failure action
- Flag effects with business logic — they should only orchestrate async operations and dispatch actions

## Selectors
- Verify selectors are memoized with `createSelector` — avoid plain property access in components
- Flag selectors that compute derived data inline in templates — move computation to the selector
- Ensure selectors are unit tested with `projector` functions

## General
- Verify the feature state is lazily registered with the routing module that loads it
- Flag `store.select` calls that return untyped `any` — ensure proper typing
- Ensure `ngrx/store-devtools` is not included in production builds
