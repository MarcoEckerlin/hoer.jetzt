package jetzt.hoer.updater.konfig;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.beans.factory.annotation.Value;

/**
 * Anmeldung fuer die Oberflaeche.
 *
 * Der Torwaechter-Pfad braucht keine: an seinem Port kommt nur an, wer im
 * internen Docker-Netz steht, und das ist Caddy. Ein Passwort davor waere
 * ein Geheimnis, das in zwei Konfigurationen gepflegt werden muss, um eine
 * Grenze zu sichern, die schon durch das Netz gezogen ist.
 *
 * Das Passwort des Verwalters steht als bcrypt-Hash in der Umgebung. Im
 * Klartext liegt es nirgends auf diesem Host - einrichten.sh zeigt es einmal
 * an und vergisst es dann.
 */
@Configuration
public class SicherheitKonfiguration {

    /**
     * Der Trennfilter muss vor die Sicherheitskette. Sonst beantwortet
     * Spring Security eine Anfrage am falschen Port erst mit einer
     * Anmeldeaufforderung - und verraet damit, dass es den Pfad gibt.
     */
    @Bean
    public FilterRegistrationBean<PortTrennung> portTrennungZuerst(PortTrennung filter) {
        FilterRegistrationBean<PortTrennung> anmeldung = new FilterRegistrationBean<>(filter);
        anmeldung.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 100);
        return anmeldung;
    }

    @Bean
    public PasswordEncoder passwortKodierer() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService verwalter(@Value("${hj.verwalter.name:verwalter}") String name,
                                        @Value("${hj.verwalter.hash}") String hash) {
        return new InMemoryUserDetailsManager(
                User.withUsername(name).password(hash).roles("VERWALTER").build());
    }

    @Bean
    public SecurityFilterChain kette(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(regeln -> regeln
                // Maschinen-Schnittstelle. Erreichbar nur am Tor-Port, dafuer
                // sorgt PortTrennung - hier steht sie nur, damit die
                // Sicherheitskette sie nicht abfaengt.
                .requestMatchers(PortTrennung.INTERN + "**").permitAll()
                .requestMatchers("/stil.css", "/anmelden").permitAll()
                .anyRequest().authenticated())
            .formLogin(anmeldung -> anmeldung
                .loginPage("/anmelden")
                .defaultSuccessUrl("/", true)
                .permitAll())
            .logout(abmeldung -> abmeldung
                .logoutUrl("/abmelden")
                .logoutSuccessUrl("/anmelden?ab"))
            // Der Torwaechter bekommt POSTs von Caddy und den Knoten, die kein
            // Formular ausgefuellt haben und kein Token mitbringen koennen.
            .csrf(schutz -> schutz.ignoringRequestMatchers(PortTrennung.INTERN + "**"))
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
