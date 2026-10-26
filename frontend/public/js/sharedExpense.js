/**
 * Despesa compartida: el sopar de 40 € entre quatre que pago jo, i els Bizums
 * que me'n tornen la part.
 *
 * Agrupats, compta la meva part (10 €), la resta queda com un deute que em
 * deuen (30 €) i els Bizums el van saldant sense comptar com a ingressos. El
 * backend ho aplica (SharedExpenseService); aquí hi ha els càlculs de la
 * pantalla, sense DOM, perquè es puguin provar sols.
 */

/** Els imports es comparen en cèntims: amb decimals, 0.1 + 0.2 no fa 0.3. */
const toCents = (value) => Math.round((Number.parseFloat(value) || 0) * 100);

const DAY = 24 * 60 * 60 * 1000;

/**
 * La meva part si es reparteix a parts iguals entre `people` persones, jo
 * inclòs. El cèntim que no es pot repartir és meu: que em deguin un cèntim de
 * menys, no de més. 40 € entre 3 → em deuen 13,33 cadascú i la meva part és 13,34.
 *
 * @returns {{ mine: number, each: number, owed: number }} en euros
 */
export function equalShare(total, people) {
    const totalCents = toCents(total);
    const count = Math.max(2, Math.floor(Number(people) || 0));
    const each = Math.floor(totalCents / count);
    const mine = totalCents - each * (count - 1);
    return { mine: mine / 100, each: each / 100, owed: (totalCents - mine) / 100 };
}

/**
 * El que em deuen, el que m'han tornat els Bizums triats i el que en falta.
 *
 * `validShare` és fals si la meva part no és de 0 a menys del total (tot meu
 * no seria cap despesa compartida), i `tooMuch` si els Bizums sumen més del
 * que em deuen: el backend ho rebutjaria igual.
 */
export function balanceOf(total, myShare, repaymentAmounts = []) {
    const totalCents = toCents(total);
    const mineCents = toCents(myShare);
    const owedCents = totalCents - mineCents;
    const repaidCents = repaymentAmounts.reduce((sum, amount) => sum + toCents(amount), 0);
    return {
        owed: owedCents / 100,
        repaid: repaidCents / 100,
        pending: (owedCents - repaidCents) / 100,
        validShare: myShare !== '' && myShare !== null && myShare !== undefined
            && mineCents >= 0 && mineCents < totalCents,
        tooMuch: repaidCents > owedCents
    };
}

const hasParts = (movement) => (movement.parts || []).length > 0;
const hasDebt = (movement) => Number(movement.deute_id) > 0;
const isTransfer = (movement) => Number(movement.compte_contrapart_id) > 0;

/** Si es pot compartir: una despesa sense dividir, sense deute i que no és cap traspàs. */
export function canShare(movement) {
    return movement.type !== 'INCOME' && !hasParts(movement) && !hasDebt(movement) && !isTransfer(movement);
}

/** Si pot tornar una despesa compartida: un ingrés sense dividir, sense deute i que no és cap traspàs. */
export function canRepay(movement) {
    return movement.type === 'INCOME' && !hasParts(movement) && !hasDebt(movement) && !isTransfer(movement);
}

/**
 * Els ingressos que poden tornar un pagament, els d'import igual a la part de
 * cadascú primer (els Bizums d'un sopar a parts iguals), i després per data.
 *
 * @param window per als moviments ja desats, de quants dies abans a quants
 *               després del pagament: un Bizum d'un any després no és d'aquest
 *               sopar. Sense, tots (a la revisió d'un extracte, que ja és d'un mes).
 */
export function repaymentCandidates(payment, movements, { each = null, window = null } = {}) {
    const paidOn = Date.parse(payment.data);
    const eachCents = each === null ? null : toCents(each);
    return movements
        .filter(movement => movement !== payment && canRepay(movement))
        .filter(movement => {
            if (!window || Number.isNaN(paidOn)) return true;
            const days = (Date.parse(movement.data) - paidOn) / DAY;
            return days >= -window.before && days <= window.after;
        })
        .sort((left, right) => {
            const leftMatches = eachCents !== null && toCents(left.cost) === eachCents;
            const rightMatches = eachCents !== null && toCents(right.cost) === eachCents;
            if (leftMatches !== rightMatches) return leftMatches ? -1 : 1;
            return String(left.data).localeCompare(String(right.data));
        });
}

/**
 * On pot anar el Bizum que arriba tard: els deutes que em deuen, amb alguna
 * cosa pendent, i on el que vaig avançar no compta, com en una despesa
 * compartida. D'un préstec on l'avançament compta, el cobrament també ha de
 * comptar (AGENTS.md, 11: o compten tots dos o cap), i aquest Bizum es marca
 * com a exclòs: aquell es vincula des de l'edició del moviment.
 */
export function openSharedExpenseDebts(debts) {
    const advanceCounts = (debt) => (debt.moviments || [])
        .some(movement => movement.type === 'EXPENSE' && !movement.exclos_pressupost);
    return debts.filter(debt => debt.direccio === 'EM_DEUEN' && toCents(debt.pendent) > 0 && !advanceCounts(debt));
}
