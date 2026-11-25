package it.chewbecca.youtubeanalytics.telegram.service;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TelegramCommandCatalog {

    private final List<CommandDescriptor> commands = List.of(
            new CommandDescriptor(
                    "/ping",
                    "/ping",
                    "Verifica che il bot e il webhook siano attivi",
                    List.of("/ping@chewbecca_strategist_bot")
            ),
            new CommandDescriptor(
                    "/help",
                    "/help",
                    "Mostra i comandi disponibili; /comandi è un alias",
                    List.of("/help@chewbecca_strategist_bot", "/comandi@chewbecca_strategist_bot")
            ),
            new CommandDescriptor(
                    "/adesso",
                    "/adesso",
                    "Analizza gli ultimi 28 giorni completi confrontandoli con i 28 precedenti",
                    List.of("/adesso", "come sta andando il canale adesso?")
            ),
            new CommandDescriptor(
                    "/topvideo",
                    "/topvideo [dataInizio dataFine]",
                    "Top 10 video per visualizzazioni; senza date usa gli ultimi 28 giorni completi",
                    List.of("/topvideo", "/topvideo 2026-07-01 2026-07-31")
            ),
            new CommandDescriptor(
                    "/topshort",
                    "/topshort [dataInizio dataFine]",
                    "Top 10 Short per visualizzazioni; senza date usa gli ultimi 28 giorni completi",
                    List.of("/topshort", "/topshort 2026-07-01 2026-07-31")
            ),
            new CommandDescriptor(
                    "/trimestre",
                    "/trimestre",
                    "Analizza gli ultimi tre mesi completi contro i tre mesi completi precedenti",
                    List.of("/trimestre", "confronta gli ultimi 3 mesi con quelli precedenti")
            ),
            new CommandDescriptor(
                    "/guadagni30",
                    "/guadagni30",
                    "Mostra i guadagni YouTube stimati degli ultimi 30 giorni completi",
                    List.of("/guadagni30", "quanto abbiamo guadagnato negli ultimi 30 giorni?")
            ),
            new CommandDescriptor(
                    "/ripartenze",
                    "/ripartenze",
                    "Confronta la stessa finestra temporale successiva a ogni ripartenza configurata",
                    List.of("/ripartenze", "confronta tutte le nostre ripartenze")
            ),
            new CommandDescriptor(
                    "/confrontomese",
                    "/confrontomese YYYY-MM",
                    "Confronta l'ultimo mese completo con il mese indicato",
                    List.of("/confrontomese 2025-09", "confronta l'ultimo mese con settembre 2025")
            ),
            new CommandDescriptor(
                    "/confrontoanno",
                    "/confrontoanno YYYY",
                    "Confronta l'anno corrente fino all'ultimo giorno completo con lo stesso intervallo dell'anno indicato",
                    List.of("/confrontoanno 2024", "confronta quest'anno con il 2024")
            ),
            new CommandDescriptor(
                    "/ultimovideo",
                    "/ultimovideo",
                    "Confronta l'ultimo video lungo con i precedenti allo stesso numero di giorni dalla pubblicazione",
                    List.of("/ultimovideo", "come sta andando l'ultimo video rispetto agli altri?")
            )
    );

    private final List<String> naturalLanguageExamples = List.of(
            "@chewbecca_strategist_bot dimmi i top video",
            "@chewbecca_strategist_bot dimmi i top Short",
            "@chewbecca_strategist_bot come sta andando il canale?",
            "@chewbecca_strategist_bot confronta gli ultimi 3 mesi con i precedenti",
            "@chewbecca_strategist_bot quanto abbiamo guadagnato negli ultimi 30 giorni?",
            "@chewbecca_strategist_bot come sta andando l'ultimo video rispetto agli altri?"
    );

    public CommandsResponse response() {
        return new CommandsResponse(
                "DETERMINISTIC_NO_AI",
                false,
                "Le richieste vengono riconosciute con regole predefinite e i calcoli sono eseguiti in Java.",
                commands,
                naturalLanguageExamples
        );
    }

    public String helpText(String botUsername) {
        String suffix = botUsername == null || botUsername.isBlank() ? "" : "@" + botUsername;
        StringBuilder help = new StringBuilder("Comandi disponibili (senza AI):\n\n");
        for (CommandDescriptor command : commands) {
            help.append(command.usage().replace(command.command(), command.command() + suffix))
                    .append("\n")
                    .append(command.description())
                    .append("\n\n");
        }
        help.append("Puoi anche menzionarmi, per esempio:\n")
                .append("@").append(botUsername).append(" dimmi i top video");
        return help.toString().trim();
    }

    public record CommandsResponse(
            String mode,
            boolean usesArtificialIntelligence,
            String note,
            List<CommandDescriptor> commands,
            List<String> naturalLanguageExamples
    ) {
    }

    public record CommandDescriptor(
            String command,
            String usage,
            String description,
            List<String> examples
    ) {
    }
}
