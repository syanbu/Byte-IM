# Development Constraints

## Android Back Navigation Semantics

The top-left back button on all pages must follow the same semantics as the Android system Back action.

- Do not implement separate back navigation logic for the top-left back button and the system Back action.
- Each page must have only one unified `onBack` / `onNavigateBack` callback.
- Both the top-left back button and `BackHandler` must call the same callback.
- Only top-level pages, such as `Messages` and `Me`, may allow the system Back action to exit or minimize the app.
- Secondary or tertiary pages must navigate back to the previous level first.

Current IM page hierarchy examples:

- `Messages` top-level page: system Back exits or minimizes the app.
- `Me` top-level page: system Back exits or minimizes the app.
- `Me -> Profile`: both the top-left back button and system Back return to `Me`.
- `Me -> Profile -> Name`: both the top-left back button and system Back return to `Profile`.
- `Chat`: both the top-left back button and system Back return to `Messages`.

Implementation suggestions:

- Child pages should not directly call Activity `finish()`.
- Internal page hierarchy should be handled by the page’s own unified back handler.
- Top-level exit logic should only be triggered after confirming that there is no child page to navigate back to.