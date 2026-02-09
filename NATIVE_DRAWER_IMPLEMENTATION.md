# Native Android Drawer Implementation

## Overview
Replaced JavaScript-based vault UI (vault.js dropdowns) with native Android navigation drawer for better reliability and UX.

## Architecture

### Data Flow
```
User taps FAB → Drawer opens → User selects project → Sessions load
User checks sessions → Taps "Load Sessions" → Files read from vault
Sessions concatenated → Injected to WebView localStorage → Available to Gradio
```

### Components

**VaultModels.kt**
- `Project`: name, sessions list, isExpanded flag
- `Session`: name, project, isSelected flag  
- `VaultItem`: Sealed class (ProjectItem | SessionItem)

**VaultAdapter.kt**
- RecyclerView adapter with two ViewHolder types
- Handles project expand/collapse
- Handles session checkbox toggling
- Updates UI dynamically

**MainActivity.kt Changes**
- Uses `activity_main.xml` layout (DrawerLayout + WebView + FAB)
- `setupDrawer()`: Initialize RecyclerView and button handlers
- `loadProjects()`: Read from AniBridge.listProjects()
- `toggleProject()`: Expand/collapse, lazy-load sessions
- `loadSelectedSessions()`: Read JSON files, extract history, concatenate
- `readSessionFile()`: Added to AniBridge for file access

### Layout Files
- `activity_main.xml`: Main DrawerLayout structure
- `drawer_header.xml`: Vault name + context status
- `item_project.xml`: Project row (📁/📂 + name)
- `item_session.xml`: Session row (checkbox + name)

## Session Loading Process

1. User checks session checkboxes
2. Taps "Load Selected Sessions" button
3. For each selected session:
   - Call `bridge.readSessionFile(project, session)`
   - Parse JSON, extract `messages` array
   - Format as `[role]: content` text
   - Append to combined context
4. Store in `localStorage.aniContext` as JSON
5. Close drawer, show toast confirmation

## Context Format
```json
{
  "sessions": 2,
  "context": "=== general/session1 ===\n[user]: ...\n[assistant]: ...\n\n=== general/session2 ===\n..."
}
```

## Future Enhancements
- Session search/filter
- Date sorting
- Session preview
- Context size indicator
- Export selected sessions
- Sync to cloud (with optional accounts)

## Testing Checklist
- [ ] Drawer opens/closes smoothly
- [ ] Projects load from vault
- [ ] Projects expand to show sessions
- [ ] Sessions can be selected via checkbox
- [ ] Multiple sessions can be selected
- [ ] "Load Sessions" reads files correctly
- [ ] Context injected to localStorage
- [ ] Gradio can read aniContext
- [ ] Works across app restarts
