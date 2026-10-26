import { createClient } from '@supabase/supabase-js'

const supabaseUrl = import.meta.env.VITE_SUPABASE_URL as string | undefined
const publishableKey = import.meta.env.VITE_SUPABASE_PUBLISHABLE_KEY as string | undefined
// Capture callback failures before the auth client removes the URL fragment.
const callback = new URLSearchParams(window.location.hash.slice(1))
export const authCallbackFailed = callback.has('error') || callback.has('error_code')

export const supabase = supabaseUrl && publishableKey ? createClient(supabaseUrl, publishableKey) : null
