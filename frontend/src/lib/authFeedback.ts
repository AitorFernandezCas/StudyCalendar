export function authErrorMessage(error: unknown) {
  const code = error && typeof error === 'object' && 'code' in error ? String(error.code) : ''
  const messages: Record<string, string> = {
    invalid_credentials: 'El email o la contraseña no son correctos.',
    email_not_confirmed: 'Confirma tu email antes de iniciar sesión. Revisa también la carpeta de spam.',
    weak_password: 'La contraseña no cumple los requisitos de seguridad. Utiliza una contraseña más larga y combina letras, números y símbolos.',
    same_password: 'Elige una contraseña distinta de la anterior.',
    over_email_send_rate_limit: 'Se han solicitado demasiados emails. Espera unos minutos antes de volver a intentarlo.',
    over_request_rate_limit: 'Hay demasiados intentos. Espera unos minutos y vuelve a intentarlo.',
    otp_expired: 'El enlace ha caducado. Solicita un nuevo email de recuperación.',
    validation_failed: 'Comprueba el email y los datos del formulario.',
    user_banned: 'No se puede acceder a esta cuenta. Contacta con la persona que administra la aplicación.',
  }
  return messages[code] || 'No se pudo completar la solicitud. Comprueba tu conexión e inténtalo de nuevo.'
}
