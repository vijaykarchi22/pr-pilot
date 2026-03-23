# Angular Component — Review Rules

Applied automatically when reviewing `*.component.ts / .html / .scss` files.

---

## Template & Binding
- Flag two-way binding (`[(ngModel)]`) on complex objects — prefer explicit `[value]` + `(change)` for clarity
- Detect direct DOM manipulation (`document.querySelector`, `ElementRef.nativeElement.*`) — prefer Renderer2 or Angular CDK
- Check that `*ngFor` always has a `trackBy` function to avoid unnecessary re-renders
- Flag deeply nested template logic — extract into sub-components or pipes
- Highlight `async` pipe usage; warn when the same observable is subscribed multiple times without `share`/`shareReplay`
- Ensure event bindings do not call expensive functions directly in the template (e.g. `(click)="compute()"` where `compute` is heavy)
- Flag string interpolation of potentially unsafe HTML — prefer `DomSanitizer` where necessary
- Verify `@Input()` properties have sensible defaults and are documented

## Change Detection
- Flag components using `ChangeDetectionStrategy.Default` that could benefit from `OnPush`
- Check that `OnPush` components do not mutate `@Input()` objects directly — they must replace the reference
- Detect manual `markForCheck()` / `detectChanges()` calls; note them and verify they are truly necessary

## Lifecycle Hooks
- Verify `ngOnDestroy` unsubscribes all subscriptions to prevent memory leaks
- Flag `Subject` / `BehaviorSubject` that are exposed publicly without being completed in `ngOnDestroy`
- Check that expensive initialisation (HTTP calls) is done in `ngOnInit`, not the constructor
- Warn when `ngOnChanges` is used without checking `SimpleChanges` for the specific property

## Component Design
- Flag components with more than ~300 lines — suggest splitting responsibilities
- Identify UI state that belongs in a service / store rather than component properties
- Flag direct service calls for data that should be managed by a parent or a state store
- Check that `@Output()` EventEmitters emit typed payloads, not raw DOM events
- Ensure accessibility attributes (`aria-*`, `role`, `tabindex`) are present on interactive elements

## SCSS / Styles
- Flag global styles defined inside a component's stylesheet — they should use `:host` scoping
- Check for magic numbers in dimensions/colours — prefer CSS custom properties or design tokens
