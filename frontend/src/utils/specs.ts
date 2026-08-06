/**
 * The AI part search returns specifications as an array of "jsonName: value" strings
 * (see AiPartSearchService's SYSTEM_PROMPT_TEMPLATE, which instructs the model to key them
 * by each spec definition's exact jsonName). Both entry paths — Quick Add and the barcode
 * scanner — need them as the `Record<string, string>` that `QuickAddRequest.specs` takes.
 *
 * Keys that match no spec definition are kept: the backend's
 * `SpecDefinitionService.canonicalizeKeys` maps known aliases onto their canonical name and
 * deliberately lets unrecognised keys through, so a later "rescan from parts" can turn them
 * into definitions.
 */
export function parseAiSpecs(raw: string[] | undefined): Record<string, string> {
  const specs: Record<string, string> = {};
  for (const entry of raw ?? []) {
    // Split on the FIRST colon only — values legitimately contain colons ("1:10", "0:00").
    const idx = entry.indexOf(':');
    if (idx === -1) continue;
    const key = entry.slice(0, idx).trim();
    const value = entry.slice(idx + 1).trim();
    if (key !== '' && value !== '') specs[key] = value;
  }
  return specs;
}
