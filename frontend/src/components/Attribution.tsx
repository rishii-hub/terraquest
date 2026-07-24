import type { Attribution as AttributionData } from '../api/types'

/**
 * CC-BY-SA credit. Deliberately on the result screen only -- a contributor's name
 * frequently gives away the country, so it must never appear over the round image.
 */
export function Attribution({ data }: { data: AttributionData }) {
  return (
    <p className="mt-3 border-t border-white/10 pt-2 text-xs text-neutral-400">
      Imagery by{' '}
      {data.profileUrl ? (
        <a href={data.profileUrl} target="_blank" rel="noreferrer" className="underline hover:text-neutral-200">
          {data.contributor}
        </a>
      ) : (
        <span className="text-neutral-300">{data.contributor}</span>
      )}
      {' · '}
      {data.licence}
      {' · '}
      <a href={data.sourceUrl} target="_blank" rel="noreferrer" className="underline hover:text-neutral-200">
        source
      </a>
    </p>
  )
}
