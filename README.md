# Echo Layer

Echo Layer is a privacy-focused Android keyboard built on top of the AOSP LatinIME codebase and extended with covert messaging and AI-assisted writing tools.

## What It Does

- Fast lightweight keyboard for everyday typing
- RU/EN typing layouts with custom themes, height, number row, and swipe cursor controls
- `Invisible mode` for hiding encrypted messages behind visible cover text
- Shared passphrase encryption for encode/decode flows
- Cover templates, first-word cover mode, emoji markers, and panic wipe
- Decode preview inside the keyboard instead of replacing the chat text
- Clipboard-aware hidden message detection while the keyboard is active
- AI rewriting before send or by long-pressing `Enter`
- Configurable AI providers:
  - OpenAI
  - OpenRouter
  - Ollama
  - Yandex
- Editable system prompt for tone and correction rules

## Core Product Areas

### Keyboard

- Adjustable keyboard height
- Optional number row
- Swipe on space to move the cursor
- Swipe on delete for selection/delete workflows
- Custom keyboard theme colors

### Invisible Messaging

- Lock key on supported RU/EN layouts
- Encrypt typed drafts into hidden carriers
- Decode hidden content from the current field or clipboard-driven flows
- Show decoded text in the keyboard candidate area
- Built-in cover templates and custom templates

### AI Rewrite

- Rewrite the current draft before sending
- Long-press `Enter` and choose `AI` to rewrite the current message without sending it
- Use a default professional editor/corrector prompt or replace it with your own

## Privacy

- No ads
- No analytics
- No cloud dependency for the keyboard itself
- AI requests are only sent to the provider you configure
- Invisible mode works locally on-device except for any optional AI rewriting you enable

## Notes

- Some chat apps normalize or strip invisible Unicode characters. For those cases Echo Layer falls back to safer carrier strategies.
- AI rewriting depends on the provider you configure and your network reachability to that provider.
- Ollama on a local network usually needs an address like `http://192.168.x.x:11434`.

## License

Echo Layer is distributed under the Apache License 2.0.

Parts of the keyboard are derived from AOSP LatinIME.
