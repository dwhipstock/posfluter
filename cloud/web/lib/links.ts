/**
 * The stable staff phone-app link for one store: <portal>/staff-app?store=<id>
 * (app/staff-app/route.ts redirects it to the store's current LAN address).
 */
export function staffAppLink(origin: string, venueId: string): string {
  return `${origin.replace(/\/+$/, "")}/staff-app?store=${encodeURIComponent(venueId)}`;
}
