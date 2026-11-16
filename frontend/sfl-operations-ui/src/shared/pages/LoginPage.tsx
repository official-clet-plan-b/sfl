import { FormEvent, useState } from 'react';
import logo from 'assets/sfl-logo.png';
import {
  Button,
  Field,
  FieldControl,
  FieldLabel,
  Input,
  Notice,
} from '@rfdtech/components';
import { signInWithConfiguredProvider } from 'shared/auth/provider';
import { directorate } from 'shared/layout/navigation';

/**
 * Sign in.
 *
 * <h2>Why this is built on the component library</h2>
 *
 * The page was specified from a shadcn block, which is not used verbatim: it ships its own `Button`,
 * `Input` and `Label`, and `@rfdtech/components` already provides all three in the dashboard's
 * theme. The form is the library's `Field` family around its `Input`, so the sign-in screen is
 * themed by the same tokens as everything behind it.
 *
 * <h2>One error message for a wrong email and a wrong password</h2>
 *
 * An earlier version told them apart - "no account for that address" versus "that password is not
 * right" - on the grounds that this is a development sign-in whose whole account list is printed on
 * the page, so there was nothing to protect. The owner asked for the single message, and that is the
 * right default to build in: the moment this page points at real accounts, distinguishing the two
 * turns the form into an account-enumeration oracle. The seeded demo accounts are not listed:
 * people sign in with the accounts they were given, and the demo accounts work only on a
 * developer's own machine.
 */
const LoginPage = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!email.trim() || !password) {
      setError('Enter your email address and password.');
      return;
    }
    /*
      Through the configured provider rather than the seeded sign-in directly. Which one runs is an
      environment decision now, and this page is deliberately the same page either way - the only
      thing it knows is that something either issued a session or gave it a sentence to show.
    */
    const result = await signInWithConfiguredProvider(email, password);
    if (!result.ok) {
      /*
        The provider's own sentence when it has one. "This account has no access to Facilities &
        Infrastructure" is the answer somebody needs; replacing it with "invalid username or
        password" would send them to reset a password that was never wrong.
      */
      setError(result.message ?? 'Invalid username/email or password.');
      setPassword('');
      return;
    }
    /*
      A full navigation rather than a router push. Everything derived from the actor - programme
      entitlement, system entitlement, the merged permission set, the landing destination - is
      computed once at module scope, which is what makes the sidebar and route guards synchronous.
      A client-side transition would leave all of it holding the pre-sign-in actor, so the signed-in
      user would land on somebody else's portal.
    */
    window.location.assign(`${import.meta.env.BASE_URL.replace(/\/$/, '')}/`);
  };

  return (
    <div className="relative flex min-h-screen flex-col items-center justify-center px-4 py-10">
      {/*
        The campus at night, with a scrim over it. The scrim is not decoration: the wordmark sits on a
        photograph whose brightness varies across the frame, and without it the white lettering falls
        to roughly 2:1 against the lit windows. `aria-hidden` because it carries nothing a screen
        reader needs.
      */}
      <div
        aria-hidden="true"
        className="absolute inset-0 bg-cover bg-center"
        style={{ backgroundImage: 'url(images/clet-campus.png)' }}
      />
      <div aria-hidden="true" className="absolute inset-0 bg-primary/70" />

      <div className="relative w-full max-w-[34rem]">
        {/*
          CLET carries the weight and the organisation name leads, because CLET is the institution and
          Safety, Facilities & Logistics is one directorate inside it. The earlier order had that
          backwards.
        */}
        <div className="mb-7 flex flex-col items-center text-center">
          <img src={logo} alt="" aria-hidden="true" className="h-16 w-16 object-contain" />
          <p className="mt-4 text-title-md font-extrabold tracking-tight text-primary-foreground">
            {directorate.parentOrganisation}
          </p>
          <h1 className="mt-1 text-theme-xl font-medium tracking-tight text-primary-foreground/80">
            {directorate.name}
          </h1>
        </div>

        <div className="rounded-2xl bg-background px-10 py-9 shadow-lg sm:px-12">
          <h2 className="text-center text-title-sm font-bold text-foreground">Welcome Back</h2>
          <p className="mx-auto mt-2 max-w-sm text-center text-sm text-muted-foreground">
            Sign in to your account to access CLET services securely from this browser.
          </p>

          <form onSubmit={submit} noValidate className="mt-8 space-y-4">
            <Field invalid={Boolean(error)}>
              <FieldLabel>Email</FieldLabel>
              <FieldControl>
                <Input
                  type="email"
                  name="username"
                  autoComplete="username"
                  value={email}
                  onChange={(event) => {
                    setEmail(event.target.value);
                    setError(null);
                  }}
                  autoFocus
                  required
                />
              </FieldControl>
            </Field>

            <Field invalid={Boolean(error)}>
              <FieldLabel>Password</FieldLabel>
              <FieldControl>
                <Input
                  type="password"
                  name="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) => {
                    setPassword(event.target.value);
                    setError(null);
                  }}
                  required
                />
              </FieldControl>
            </Field>

            {error && (
              <Notice variant="error" title="Could not sign you in">
                <p className="text-sm">{error}</p>
              </Notice>
            )}

            <div className="pt-2">
              <Button type="submit" variant="primary" size="lg" className="w-full">
                Sign in
              </Button>
            </div>
          </form>

        </div>
      </div>
    </div>
  );
};

export default LoginPage;
